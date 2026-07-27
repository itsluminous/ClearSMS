package app.clearsms.data.repository

import app.clearsms.data.db.AccountDao
import app.clearsms.data.db.AccountEntity
import app.clearsms.data.db.ReminderDao
import app.clearsms.data.db.ReminderEntity
import app.clearsms.data.db.TransactionDao
import app.clearsms.data.db.TransactionEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * Default [FinanceRepository] backed by Room DAOs.
 *
 * Reminder flows are de-duplicated per bill at the read layer — see
 * [ReminderDeduplication] for the identity and merge rules.
 */
class FinanceRepositoryImpl(
    private val transactionDao: TransactionDao,
    private val accountDao: AccountDao,
    private val reminderDao: ReminderDao,
) : FinanceRepository {
    override fun observeTransactions(): Flow<List<TransactionEntity>> = transactionDao.observeAll()

    override fun observeLatestTransactions(limit: Int): Flow<List<TransactionEntity>> = transactionDao.observeLatest(limit)

    override fun observeTransactionsByAccount(accountNumber: String): Flow<List<TransactionEntity>> =
        transactionDao.observeByAccount(accountNumber)

    override fun observeTransactionsByAccount(
        accountNumber: String,
        limit: Int,
    ): Flow<List<TransactionEntity>> = transactionDao.observeByAccountLimited(accountNumber, limit)

    override suspend fun latestTransactionForAccount(
        accountNumber: String,
        bankName: String,
    ): TransactionEntity? =
        transactionDao.latestForAccount(accountNumber, bankName)
            ?: transactionDao.latestForAccountNumber(accountNumber)

    override fun observeAccounts(): Flow<List<AccountEntity>> = accountDao.observeAll()

    override fun observeReminders(): Flow<List<ReminderEntity>> = reminderDao.observeUpcoming(0L).map(ReminderDeduplication::dedupe)

    override fun observeUpcomingReminders(nowMs: Long): Flow<List<ReminderEntity>> =
        reminderDao.observeUpcoming(nowMs).map(ReminderDeduplication::dedupe)

    override fun observePastReminders(nowMs: Long): Flow<List<ReminderEntity>> =
        reminderDao.observePast(nowMs).map(ReminderDeduplication::dedupe)

    override suspend fun setCardLimit(
        accountId: Long,
        limit: Double?,
    ) = accountDao.setCreditLimit(accountId, limit)

    override suspend fun addNote(
        transactionId: Long,
        note: String?,
    ) = transactionDao.setNote(transactionId, note)
}
