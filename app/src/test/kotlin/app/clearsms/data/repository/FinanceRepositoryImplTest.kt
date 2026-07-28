package app.clearsms.data.repository

import app.clearsms.data.db.AccountDao
import app.clearsms.data.db.AccountEntity
import app.clearsms.data.db.ReminderDao
import app.clearsms.data.db.ReminderEntity
import app.clearsms.data.db.TransactionDao
import app.clearsms.data.db.TransactionEntity
import app.clearsms.domain.model.ReminderType
import app.clearsms.domain.model.TransactionType
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Test

class FinanceRepositoryImplTest {
    private fun reminder(
        id: Long,
        createdAt: Long,
        dueDate: Long? = 1_000_000L,
    ) = ReminderEntity(
        id = id,
        type = ReminderType.CREDIT_CARD,
        dueDate = dueDate,
        totalDue = 100.0 * id,
        minDue = null,
        accountLast4 = "1234",
        bankName = "IDFC FIRST",
        rawSmsId = id * 10,
        createdAt = createdAt,
    )

    private fun transaction(
        id: Long,
        account: String,
        bank: String,
        timestamp: Long,
    ) = TransactionEntity(
        id = id,
        amount = 10.0,
        type = TransactionType.DEBIT,
        accountNumber = account,
        bankName = bank,
        timestamp = timestamp,
        rawSmsId = id * 10,
    )

    @Test
    fun `upcoming and past reminder flows are de-duplicated per bill`() =
        runTest {
            val duplicates = listOf(reminder(id = 1, createdAt = 1_000), reminder(id = 2, createdAt = 2_000))
            val repository =
                FinanceRepositoryImpl(
                    transactionDao = FakeTransactionDao(),
                    accountDao = FakeAccountDao(),
                    reminderDao = FakeReminderDao(upcoming = duplicates, past = duplicates),
                )

            val upcoming = repository.observeUpcomingReminders(0L).first()
            val past = repository.observePastReminders(Long.MAX_VALUE).first()

            assertThat(upcoming).hasSize(1)
            assertThat(upcoming.single().id).isEqualTo(2)
            assertThat(past).hasSize(1)
            assertThat(past.single().id).isEqualTo(2)
        }

    @Test
    fun `latest transaction for account never falls back to the account number alone`() =
        runTest {
            val tx = transaction(id = 1, account = "1234", bank = "IDFC FIRST", timestamp = 5_000)
            val repository =
                FinanceRepositoryImpl(
                    transactionDao = FakeTransactionDao(transactions = listOf(tx)),
                    accountDao = FakeAccountDao(),
                    reminderDao = FakeReminderDao(),
                )

            assertThat(repository.latestTransactionForAccount("1234", "IDFC FIRST")).isEqualTo(tx)
            // A last-4 is not an identity: another bank's account sharing
            // the tail must NOT surface this bank's message.
            assertThat(repository.latestTransactionForAccount("1234", "Some Other Bank")).isNull()
            assertThat(repository.latestTransactionForAccount("0000", "IDFC")).isNull()
        }
}

private class FakeTransactionDao(
    private val transactions: List<TransactionEntity> = emptyList(),
) : TransactionDao {
    override fun observeAll(): Flow<List<TransactionEntity>> = flowOf(transactions)

    override fun observeLatest(limit: Int): Flow<List<TransactionEntity>> = flowOf(transactions.take(limit))

    private fun forAccount(
        accountNumber: String,
        bankName: String,
    ) = transactions.filter { it.accountNumber == accountNumber && it.bankName == bankName }

    override fun observeByAccount(
        accountNumber: String,
        bankName: String,
    ): Flow<List<TransactionEntity>> = flowOf(forAccount(accountNumber, bankName))

    override fun observeByAccountLimited(
        accountNumber: String,
        bankName: String,
        limit: Int,
    ): Flow<List<TransactionEntity>> = flowOf(forAccount(accountNumber, bankName).take(limit))

    override suspend fun latestForAccount(
        accountNumber: String,
        bankName: String,
    ): TransactionEntity? = forAccount(accountNumber, bankName).maxByOrNull { it.timestamp }

    override suspend fun findByRawSmsId(rawSmsId: Long): TransactionEntity? = transactions.find { it.rawSmsId == rawSmsId }

    override suspend fun findByReference(
        normalizedReference: String,
        accountNumber: String,
    ): List<TransactionEntity> =
        transactions.filter {
            it.referenceNumber.equals(normalizedReference, ignoreCase = true) && it.accountNumber == accountNumber
        }

    override suspend fun findNearby(
        amount: Double,
        type: TransactionType,
        accountNumber: String,
        bankName: String,
        fromTs: Long,
        toTs: Long,
    ): List<TransactionEntity> =
        transactions.filter {
            it.amount == amount &&
                it.type == type &&
                it.accountNumber == accountNumber &&
                it.bankName == bankName &&
                it.timestamp in fromTs..toTs
        }

    override suspend fun update(transaction: TransactionEntity) = Unit

    override suspend fun getAll(): List<TransactionEntity> = transactions

    override suspend fun insert(transaction: TransactionEntity): Long = transaction.id

    override suspend fun insertAll(transactions: List<TransactionEntity>) = Unit

    override suspend fun setNote(
        transactionId: Long,
        note: String?,
    ) = Unit

    override suspend fun deleteByRawSmsId(rawSmsId: Long) = Unit

    override suspend fun deleteAll() = Unit
}

private class FakeAccountDao : AccountDao {
    override fun observeAll(): Flow<List<AccountEntity>> = flowOf(emptyList())

    override suspend fun find(
        accountNumber: String,
        bankName: String,
    ): AccountEntity? = null

    override suspend fun findBlankBank(
        accountNumber: String,
        type: app.clearsms.domain.model.AccountType,
    ): AccountEntity? = null

    override suspend fun findByNumber(accountNumber: String): List<AccountEntity> = emptyList()

    override suspend fun getAll(): List<AccountEntity> = emptyList()

    override suspend fun insert(account: AccountEntity): Long = account.id

    override suspend fun insertAll(accounts: List<AccountEntity>) = Unit

    override suspend fun update(account: AccountEntity) = Unit

    override suspend fun deleteAll() = Unit
}

private class FakeReminderDao(
    private val upcoming: List<ReminderEntity> = emptyList(),
    private val past: List<ReminderEntity> = emptyList(),
) : ReminderDao {
    override fun observeUpcoming(nowMs: Long): Flow<List<ReminderEntity>> = flowOf(upcoming)

    override fun observePast(nowMs: Long): Flow<List<ReminderEntity>> = flowOf(past)

    override suspend fun findByRawSmsId(rawSmsId: Long): ReminderEntity? = (upcoming + past).find { it.rawSmsId == rawSmsId }

    override suspend fun getAll(): List<ReminderEntity> = upcoming + past

    override suspend fun insert(reminder: ReminderEntity): Long = reminder.id

    override suspend fun insertAll(reminders: List<ReminderEntity>) = Unit

    override suspend fun deleteById(id: Long) = Unit

    override suspend fun deleteByRawSmsId(rawSmsId: Long) = Unit

    override suspend fun deleteAll() = Unit
}
