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

    /** Actionable alerts under the age policy in [ReminderBucketing]; [nowMs] = start-of-today cutoff. */
    fun observeUpcomingReminders(nowMs: Long): Flow<List<ReminderEntity>>

    /** The "Older alerts" section: dismissed + expired entries, newest first. */
    fun observePastReminders(nowMs: Long): Flow<List<ReminderEntity>>

    /**
     * Flags the reminder (and every duplicate sharing its identity) as
     * dismissed - it moves to Older instead of being deleted.
     */
    suspend fun dismissReminder(
        reminderId: Long,
        dismissedAt: Long,
    )

    /** Un-dismisses the reminder group - the card returns to the active list (if still in window). */
    suspend fun restoreReminder(reminderId: Long)

    /** Permanently deletes the reminder group (the Older section's delete action). */
    suspend fun deleteReminderForever(reminderId: Long)

    /** Purges Older rows past the retention window. @return purged row count. */
    suspend fun purgeExpiredReminders(nowMs: Long): Int

    suspend fun addNote(
        transactionId: Long,
        note: String?,
    )
}
