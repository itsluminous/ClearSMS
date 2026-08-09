package app.clearsms.ui.finance

import app.clearsms.data.db.AccountEntity
import app.clearsms.data.db.TransactionEntity
import app.clearsms.domain.model.AccountType
import app.clearsms.domain.model.MerchantCategory
import app.clearsms.domain.model.TransactionType

/** Headline totals for the month-summary card, after the exclusion rules. */
data class MonthTotals(
    val net: Double,
    val debits: Double,
    val credits: Double,
    /** Counted transactions (all / debit / credit) after exclusions. */
    val txCount: Int,
    val debitCount: Int,
    val creditCount: Int,
    /** Rows left out of the totals (still visible in the transaction list). */
    val excludedCount: Int,
    val excludedTotal: Double,
)

/**
 * The month-summary aggregation, with the double-counting exclusions.
 *
 * The headline must reflect real money in and out of the user's pocket, but
 * one payment routinely produces several SMS-derived rows: the bank debit
 * that pays a credit-card bill, the card-side "payment received" credit for
 * the same rupees, and card bill-DUE notices parsed as debits. Summing all
 * of them counted the same money two or three times (observed on a real
 * inbox: one card bill inflated the month by ~₹4.8L across three rows).
 *
 * Excluded from the totals - never from the transaction list:
 * 1. [MerchantCategory.TRANSFER] rows, either direction. Transfers between
 *    the user's own accounts and payments routed via intermediaries (CRED,
 *    UPI card-bill flows) move money, they are not spend or income.
 * 2. CREDITS onto a credit-card account. A card is never paid BY someone
 *    else's money: these are the user's own bill payments (already a bank
 *    debit or a TRANSFER), refunds of spends already counted as "out", or
 *    cashback - none of them income.
 * 3. Merchant-less DEBITS on a credit-card account. Genuine card purchases
 *    always carry the merchant from the "Spent … at X" alert; a debit row
 *    on a card with no merchant is a bill-due / statement figure that
 *    leaked in as a transaction, not money moving.
 *
 * Deliberately still counted: investment contributions (SIP/RD/NPS) as
 * "out" - money genuinely left the account this month even if it bought an
 * asset - and ordinary card spends (real expenditure at a merchant).
 */
object MonthSummary {
    /**
     * Identity of the known credit cards for exclusion checks. Keyed by
     * account row id (for linked transactions) plus the exact
     * (last-4, bank) pair (for unlinked legacy rows) - never the last-4
     * alone, which would misclassify a savings transaction whose tail a
     * card at another bank happens to share.
     */
    data class CardIdentity(
        val ids: Set<Long>,
        val numberBankPairs: Set<Pair<String, String>>,
    )

    /** Card identity keys derived from the account list. */
    fun cardIdentity(accounts: List<AccountEntity>): CardIdentity {
        val cards = accounts.filter { it.type == AccountType.CREDIT_CARD }
        return CardIdentity(
            ids = cards.map { it.id }.toSet(),
            numberBankPairs =
                cards
                    .asSequence()
                    .filter { it.accountNumber.isNotBlank() }
                    .map { it.accountNumber to it.bankName }
                    .toSet(),
        )
    }

    /** True when [tx] must not contribute to the month's spend/income totals. */
    fun isExcluded(
        tx: TransactionEntity,
        cards: CardIdentity,
    ): Boolean {
        if (tx.category == MerchantCategory.TRANSFER) return true
        val onCard =
            tx.accountId?.let { it in cards.ids }
                ?: (tx.accountNumber.isNotBlank() && (tx.accountNumber to tx.bankName) in cards.numberBankPairs)
        if (!onCard) return false
        return when (tx.type) {
            TransactionType.CREDIT -> true
            TransactionType.DEBIT -> tx.merchantName.isNullOrBlank()
        }
    }

    /** Totals over [monthTransactions] with the exclusion rules applied. */
    fun compute(
        monthTransactions: List<TransactionEntity>,
        cards: CardIdentity,
    ): MonthTotals {
        val (excluded, counted) = monthTransactions.partition { isExcluded(it, cards) }
        val debits = counted.filter { it.type == TransactionType.DEBIT }
        val credits = counted.filter { it.type == TransactionType.CREDIT }
        val debitTotal = debits.sumOf { it.amount }
        val creditTotal = credits.sumOf { it.amount }
        return MonthTotals(
            net = creditTotal - debitTotal,
            debits = debitTotal,
            credits = creditTotal,
            txCount = counted.size,
            debitCount = debits.size,
            creditCount = credits.size,
            excludedCount = excluded.size,
            excludedTotal = excluded.sumOf { it.amount },
        )
    }
}
