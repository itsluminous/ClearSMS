package app.clearsms.ui.finance

import app.clearsms.data.db.AccountEntity
import app.clearsms.data.db.TransactionEntity
import app.clearsms.domain.model.AccountType
import app.clearsms.domain.model.MerchantCategory
import app.clearsms.domain.model.TransactionType
import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * The month-summary exclusion rules ([MonthSummary]): self-transfers and
 * credit-card bill payments must not double count, ordinary spend and
 * income must. The last test reproduces (digits masked, shape exact) the
 * pattern found on a real device where one card-bill payment inflated the
 * month across three rows.
 */
class MonthSummaryTest {
    private var nextId = 1L

    private fun tx(
        amount: Double,
        type: TransactionType,
        account: String = "8709",
        merchant: String? = "Merchant",
        category: MerchantCategory = MerchantCategory.OTHER,
    ): TransactionEntity {
        val id = nextId++
        return TransactionEntity(
            id = id,
            amount = amount,
            type = type,
            merchantName = merchant,
            accountNumber = account,
            bankName = "Bank",
            timestamp = 1_700_000_000_000 + id,
            category = category,
            rawSmsId = id,
        )
    }

    private fun account(
        number: String,
        type: AccountType,
    ) = AccountEntity(accountNumber = number, bankName = "Bank", type = type, lastUpdated = 0L)

    private val cards = MonthSummary.cardIdentity(listOf(account("5106", AccountType.CREDIT_CARD)))

    @Test
    fun `card identity ignores blanks and non-cards`() {
        val identity =
            MonthSummary.cardIdentity(
                listOf(
                    account("5106", AccountType.CREDIT_CARD),
                    account("", AccountType.CREDIT_CARD),
                    account("8709", AccountType.SAVINGS),
                ),
            )
        assertThat(identity.numberBankPairs).containsExactly("5106" to "Bank")
    }

    @Test
    fun `savings transaction sharing a card's last-4 at another bank is never treated as on-card`() {
        // Card *8709 at one bank, savings *8709 at another: the savings
        // credit must stay counted as income, not excluded as a card credit.
        val identity =
            MonthSummary.cardIdentity(
                listOf(
                    AccountEntity(id = 1, accountNumber = "8709", bankName = "Card Bank", type = AccountType.CREDIT_CARD, lastUpdated = 0L),
                    AccountEntity(id = 2, accountNumber = "8709", bankName = "Bank", type = AccountType.SAVINGS, lastUpdated = 0L),
                ),
            )
        val savingsCredit = tx(75_000.0, TransactionType.CREDIT, account = "8709", merchant = null)
        assertThat(MonthSummary.isExcluded(savingsCredit, identity)).isFalse()
        // The same row LINKED to the card account is on-card and excluded.
        assertThat(MonthSummary.isExcluded(savingsCredit.copy(accountId = 1), identity)).isTrue()
        // Linked to the savings account: counted, even though a card at
        // another bank shares the tail.
        assertThat(MonthSummary.isExcluded(savingsCredit.copy(accountId = 2), identity)).isFalse()
    }

    @Test
    fun `transfer rows are excluded in both directions`() {
        assertThat(
            MonthSummary.isExcluded(tx(161_849.0, TransactionType.DEBIT, merchant = "CRED", category = MerchantCategory.TRANSFER), cards),
        ).isTrue()
        assertThat(MonthSummary.isExcluded(tx(9_480.0, TransactionType.CREDIT, category = MerchantCategory.TRANSFER), cards)).isTrue()
    }

    @Test
    fun `credits onto a credit card are never income`() {
        // Bill payment received, refund, cashback — all the user's own money
        // or a reduction of spend already counted as "out".
        assertThat(MonthSummary.isExcluded(tx(161_849.0, TransactionType.CREDIT, account = "5106", merchant = null), cards)).isTrue()
        assertThat(MonthSummary.isExcluded(tx(49_499.0, TransactionType.CREDIT, account = "5106", merchant = "AMAZON"), cards)).isTrue()
    }

    @Test
    fun `merchant-less debits on a credit card are bill-due leaks - excluded`() {
        assertThat(MonthSummary.isExcluded(tx(161_849.0, TransactionType.DEBIT, account = "5106", merchant = null), cards)).isTrue()
        assertThat(MonthSummary.isExcluded(tx(100.0, TransactionType.DEBIT, account = "5106", merchant = " "), cards)).isTrue()
    }

    @Test
    fun `ordinary spend and income stay counted - including card spends and investments`() {
        assertThat(MonthSummary.isExcluded(tx(500.0, TransactionType.DEBIT, merchant = "Grocer"), cards)).isFalse()
        assertThat(MonthSummary.isExcluded(tx(75_000.0, TransactionType.CREDIT, merchant = "SALARY"), cards)).isFalse()
        // A genuine card purchase carries its merchant.
        assertThat(
            MonthSummary.isExcluded(tx(40_303.0, TransactionType.DEBIT, account = "5106", merchant = "Flipkart In"), cards),
        ).isFalse()
        // Investment contributions are real money out this month.
        assertThat(
            MonthSummary.isExcluded(
                tx(13_000.0, TransactionType.DEBIT, merchant = "RD Installment", category = MerchantCategory.INVESTMENT),
                cards,
            ),
        ).isFalse()
    }

    @Test
    fun `debits on non-card accounts count even without a merchant`() {
        assertThat(MonthSummary.isExcluded(tx(8_300.0, TransactionType.DEBIT, account = "0502", merchant = null), cards)).isFalse()
    }

    @Test
    fun `compute reproduces the device double-counting pattern and corrects it`() {
        // One ₹1,61,849 card bill produced THREE rows on the real device:
        // the bank-side UPI transfer to CRED (TRANSFER debit), the card-side
        // "payment received" credit, and the bill-DUE notice parsed as a
        // merchant-less card debit. Plus a self-transfer credit and genuine
        // activity.
        val monthTxs =
            listOf(
                // Genuine activity — must be counted:
                tx(75_000.0, TransactionType.CREDIT, account = "0502", merchant = "SALARY"),
                tx(40_303.0, TransactionType.DEBIT, account = "5106", merchant = "Flipkart In"),
                tx(13_000.0, TransactionType.DEBIT, merchant = "RD Installment", category = MerchantCategory.INVESTMENT),
                // The triple-counted card bill — all excluded:
                tx(161_849.0, TransactionType.DEBIT, merchant = "CRED", category = MerchantCategory.TRANSFER),
                tx(161_849.0, TransactionType.CREDIT, account = "5106", merchant = null),
                tx(161_849.0, TransactionType.DEBIT, account = "5106", merchant = null),
                // A transfer between the user's own accounts — excluded:
                tx(9_480.0, TransactionType.CREDIT, category = MerchantCategory.TRANSFER),
            )
        val totals = MonthSummary.compute(monthTxs, cards)
        assertThat(totals.credits).isEqualTo(75_000.0)
        assertThat(totals.debits).isEqualTo(40_303.0 + 13_000.0)
        assertThat(totals.net).isEqualTo(75_000.0 - 53_303.0)
        assertThat(totals.txCount).isEqualTo(3)
        assertThat(totals.debitCount).isEqualTo(2)
        assertThat(totals.creditCount).isEqualTo(1)
        assertThat(totals.excludedCount).isEqualTo(4)
        assertThat(totals.excludedTotal).isEqualTo(161_849.0 * 3 + 9_480.0)
    }

    @Test
    fun `empty month yields zero totals and zero exclusions`() {
        val totals = MonthSummary.compute(emptyList(), cards)
        assertThat(totals.net).isEqualTo(0.0)
        assertThat(totals.txCount).isEqualTo(0)
        assertThat(totals.excludedCount).isEqualTo(0)
    }
}
