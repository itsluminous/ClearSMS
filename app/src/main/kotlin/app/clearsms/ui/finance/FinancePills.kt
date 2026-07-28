package app.clearsms.ui.finance

import app.clearsms.data.db.AccountEntity
import app.clearsms.data.db.TransactionEntity
import app.clearsms.domain.model.AccountType
import app.clearsms.domain.model.FinanceTab
import java.time.Instant
import java.time.YearMonth
import java.time.ZoneId

/** Badge counts for the Finance filter pills, derived off the main thread in the ViewModel. */
object FinancePills {
    /**
     * Accounts = bank accounts and wallets, Credit cards = credit card
     * accounts, Transactions = transactions dated inside [month].
     */
    fun counts(
        accounts: List<AccountEntity>,
        transactions: List<TransactionEntity>,
        month: YearMonth,
        zone: ZoneId = ZoneId.systemDefault(),
    ): Map<FinanceTab, Int> {
        val cards = accounts.count { it.type == AccountType.CREDIT_CARD }
        val monthTxs =
            transactions.filter {
                YearMonth.from(Instant.ofEpochMilli(it.timestamp).atZone(zone)) == month
            }
        return mapOf(
            FinanceTab.ACCOUNTS to (accounts.size - cards),
            FinanceTab.CREDIT_CARDS to cards,
            FinanceTab.TRANSACTIONS to monthTxs.size,
            FinanceTab.RECHARGES to monthTxs.count(RechargeTransactions::isRecharge),
        )
    }
}
