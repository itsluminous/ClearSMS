package app.clearsms.data.repository

import app.clearsms.data.db.AccountDao
import app.clearsms.data.db.AccountEntity
import app.clearsms.data.db.ReminderDao
import app.clearsms.data.db.ReminderEntity
import app.clearsms.data.db.TransactionDao
import app.clearsms.data.db.TransactionEntity
import kotlinx.coroutines.flow.Flow

/** Default [FinanceRepository] backed by Room DAOs. */
class FinanceRepositoryImpl(
    private val transactionDao: TransactionDao,
    private val accountDao: AccountDao,
    private val reminderDao: ReminderDao,
) : FinanceRepository {
    override fun observeTransactions(): Flow<List<TransactionEntity>> = transactionDao.observeAll()

    override fun observeTransactionsByAccount(accountNumber: String): Flow<List<TransactionEntity>> =
        transactionDao.observeByAccount(accountNumber)

    override fun observeAccounts(): Flow<List<AccountEntity>> = accountDao.observeAll()

    override fun observeReminders(): Flow<List<ReminderEntity>> = reminderDao.observeUpcoming(0L)

    override fun observeUpcomingReminders(nowMs: Long): Flow<List<ReminderEntity>> = reminderDao.observeUpcoming(nowMs)

    override fun observePastReminders(nowMs: Long): Flow<List<ReminderEntity>> = reminderDao.observePast(nowMs)

    override suspend fun setCardLimit(
        accountId: Long,
        limit: Double?,
    ) = accountDao.setCreditLimit(accountId, limit)

    override suspend fun addNote(
        transactionId: Long,
        note: String?,
    ) = transactionDao.setNote(transactionId, note)
}
