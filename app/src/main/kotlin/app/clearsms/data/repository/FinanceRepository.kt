package app.clearsms.data.repository

import app.clearsms.data.db.AccountEntity
import app.clearsms.data.db.ReminderEntity
import app.clearsms.data.db.TransactionEntity
import kotlinx.coroutines.flow.Flow

/** Access to extracted finance data: transactions, accounts and reminders. */
interface FinanceRepository {
    fun observeTransactions(): Flow<List<TransactionEntity>>

    fun observeTransactionsByAccount(accountNumber: String): Flow<List<TransactionEntity>>

    fun observeAccounts(): Flow<List<AccountEntity>>

    fun observeReminders(): Flow<List<ReminderEntity>>

    fun observeUpcomingReminders(nowMs: Long): Flow<List<ReminderEntity>>

    fun observePastReminders(nowMs: Long): Flow<List<ReminderEntity>>

    suspend fun setCardLimit(
        accountId: Long,
        limit: Double?,
    )

    suspend fun addNote(
        transactionId: Long,
        note: String?,
    )
}
