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
 * Excluded from the totals — never from the transaction list:
 * 1. [MerchantCategory.TRANSFER] rows, either direction. Transfers between
 *    the user's own accounts and payments routed via intermediaries (CRED,
 *    UPI card-bill flows) move money, they are not spend or income.
 * 2. CREDITS onto a credit-card account. A card is never paid BY someone
 *    else's money: these are the user's own bill payments (already a bank
 *    debit or a TRANSFER), refunds of spends already counted as "out", or
 *    cashback — none of them income.
 * 3. Merchant-less DEBITS on a credit-card account. Genuine card purchases
 *    always carry the merchant from the "Spent … at X" alert; a debit row
 *    on a card with no merchant is a bill-due / statement figure that
 *    leaked in as a transaction, not money moving.
 *
 * Deliberately still counted: investment contributions (SIP/RD/NPS) as
 * "out" — money genuinely left the account this month even if it bought an
 * asset — and ordinary card spends (real expenditure at a merchant).
 */
object MonthSummary {
    /** Non-blank last-4 numbers of the known credit cards, for exclusion checks. */
    fun creditCardNumbers(accounts: List<AccountEntity>): Set<String> =
        accounts
            .asSequence()
            .filter { it.type == AccountType.CREDIT_CARD }
            .map { it.accountNumber }
            .filter { it.isNotBlank() }
            .toSet()

    /** True when [tx] must not contribute to the month's spend/income totals. */
    fun isExcluded(
        tx: TransactionEntity,
        creditCardNumbers: Set<String>,
    ): Boolean {
        if (tx.category == MerchantCategory.TRANSFER) return true
        val onCard = tx.accountNumber.isNotBlank() && tx.accountNumber in creditCardNumbers
        if (!onCard) return false
        return when (tx.type) {
            TransactionType.CREDIT -> true
            TransactionType.DEBIT -> tx.merchantName.isNullOrBlank()
        }
    }

    /** Totals over [monthTransactions] with the exclusion rules applied. */
    fun compute(
        monthTransactions: List<TransactionEntity>,
        creditCardNumbers: Set<String>,
    ): MonthTotals {
        val (excluded, counted) = monthTransactions.partition { isExcluded(it, creditCardNumbers) }
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
