package app.clearsms.data.repository

import app.clearsms.data.db.AccountEntity
import app.clearsms.data.db.ReminderEntity
import app.clearsms.data.db.TransactionEntity
import kotlinx.coroutines.flow.Flow

/** Access to extracted finance data: transactions, accounts and reminders. */
interface FinanceRepository {
    fun observeTransactions(): Flow<List<TransactionEntity>>

    /** Newest [limit] transactions - backs the growing "load more" list. */
    fun observeLatestTransactions(limit: Int): Flow<List<TransactionEntity>>

    /** Transactions of ONE account, identified by last-4 AND bank - never the number alone. */
    fun observeTransactionsByAccount(
        accountNumber: String,
        bankName: String,
    ): Flow<List<TransactionEntity>>

    /** Newest [limit] transactions for one account - account-detail "load more" list. */
    fun observeTransactionsByAccount(
        accountNumber: String,
        bankName: String,
        limit: Int,
    ): Flow<List<TransactionEntity>>

    /** The most recent transaction for an account/card - the message behind its latest update. */
    suspend fun latestTransactionForAccount(
        accountNumber: String,
        bankName: String,
    ): TransactionEntity?

    fun observeAccounts(): Flow<List<AccountEntity>>

    fun observeReminders(): Flow<List<ReminderEntity>>

    fun observeUpcomingReminders(nowMs: Long): Flow<List<ReminderEntity>>

    fun observePastReminders(nowMs: Long): Flow<List<ReminderEntity>>

    suspend fun addNote(
        transactionId: Long,
        note: String?,
    )
}
