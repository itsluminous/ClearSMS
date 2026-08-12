package app.clearsms.data.repository

import app.clearsms.data.db.AccountDao
import app.clearsms.data.db.AccountEntity
import app.clearsms.data.db.ReminderDao
import app.clearsms.data.db.ReminderEntity
import app.clearsms.data.db.TransactionDao
import app.clearsms.data.db.TransactionEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.util.concurrent.TimeUnit

/**
 * Default [FinanceRepository] backed by Room DAOs.
 *
 * Reminder flows are de-duplicated per bill at the read layer - see
 * [ReminderDeduplication] for the identity and merge rules.
 */
class FinanceRepositoryImpl(
    private val transactionDao: TransactionDao,
    private val accountDao: AccountDao,
    private val reminderDao: ReminderDao,
) : FinanceRepository {
    override fun observeTransactions(): Flow<List<TransactionEntity>> = transactionDao.observeAll()

    override fun observeLatestTransactions(limit: Int): Flow<List<TransactionEntity>> = transactionDao.observeLatest(limit)

    override fun observeTransactionsByAccount(
        accountNumber: String,
        bankName: String,
    ): Flow<List<TransactionEntity>> = transactionDao.observeByAccount(accountNumber, bankName)

    override fun observeTransactionsByAccount(
        accountNumber: String,
        bankName: String,
        limit: Int,
    ): Flow<List<TransactionEntity>> = transactionDao.observeByAccountLimited(accountNumber, bankName, limit)

    override suspend fun latestTransactionForAccount(
        accountNumber: String,
        bankName: String,
    ): TransactionEntity? = transactionDao.latestForAccount(accountNumber, bankName)

    override fun observeAccounts(): Flow<List<AccountEntity>> = accountDao.observeAll()

    override fun observeReminders(): Flow<List<ReminderEntity>> =
        reminderDao.observeAll().map { ReminderBucketing.bucket(it, nowMs = 0L).active }

    override fun observeUpcomingReminders(nowMs: Long): Flow<List<ReminderEntity>> =
        reminderDao.observeAll().map { ReminderBucketing.bucket(it, nowMs).active }

    override fun observePastReminders(nowMs: Long): Flow<List<ReminderEntity>> =
        reminderDao.observeAll().map { ReminderBucketing.bucket(it, nowMs).older }

    override suspend fun dismissReminder(
        reminderId: Long,
        dismissedAt: Long,
    ) = reminderDao.setDismissed(identityGroupIds(reminderId), dismissedAt)

    override suspend fun restoreReminder(reminderId: Long) = reminderDao.setDismissed(identityGroupIds(reminderId), null)

    override suspend fun deleteReminderForever(reminderId: Long) = reminderDao.deleteByIds(identityGroupIds(reminderId))

    override suspend fun purgeExpiredReminders(nowMs: Long): Int {
        val retention = ReminderBucketing.retentionMs()
        return reminderDao.purgeExpired(
            dismissedCutoffMs = nowMs - retention,
            dueCutoffMs = nowMs - retention,
            deliveryCreatedCutoffMs =
                nowMs - retention - TimeUnit.DAYS.toMillis(ReminderBucketing.UNDATED_DELIVERY_ACTIVE_DAYS),
            billCreatedCutoffMs =
                nowMs - retention - TimeUnit.DAYS.toMillis(ReminderBucketing.UNDATED_BILL_ACTIVE_DAYS),
        )
    }

    /**
     * Row ids of every reminder sharing [reminderId]'s logical identity.
     * Dismiss/restore/delete act on the whole duplicate group - a surviving
     * duplicate row would otherwise resurrect the card on the next read.
     */
    private suspend fun identityGroupIds(reminderId: Long): List<Long> {
        val all = reminderDao.getAll()
        val target = all.find { it.id == reminderId } ?: return emptyList()
        val identity = ReminderDeduplication.identityOf(target)
        return all.filter { ReminderDeduplication.identityOf(it) == identity }.map { it.id }
    }

    override suspend fun addNote(
        transactionId: Long,
        note: String?,
    ) = transactionDao.setNote(transactionId, note)
}
