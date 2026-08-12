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
            // Same card, same due day: one future-dated pair (active) plus
            // one past-dated pair (older) - each collapses to its newest row.
            val futureDue = System.currentTimeMillis() + 5 * 86_400_000L
            val active = listOf(reminder(1, createdAt = 1_000, dueDate = futureDue), reminder(2, createdAt = 2_000, dueDate = futureDue))
            val expired = listOf(reminder(3, createdAt = 1_000, dueDate = 1_000L), reminder(4, createdAt = 2_000, dueDate = 1_000L))
            val repository =
                FinanceRepositoryImpl(
                    transactionDao = FakeTransactionDao(),
                    accountDao = FakeAccountDao(),
                    reminderDao = FakeReminderDao(active + expired),
                )

            val nowMs = System.currentTimeMillis()
            val upcoming = repository.observeUpcomingReminders(nowMs).first()
            val past = repository.observePastReminders(nowMs).first()

            assertThat(upcoming).hasSize(1)
            assertThat(upcoming.single().id).isEqualTo(2)
            assertThat(past).hasSize(1)
            assertThat(past.single().id).isEqualTo(4)
        }

    @Test
    fun `dismiss flags the whole duplicate group instead of deleting`() =
        runTest {
            val duplicates = listOf(reminder(1, createdAt = 1_000), reminder(2, createdAt = 2_000))
            val dao = FakeReminderDao(duplicates)
            val repository =
                FinanceRepositoryImpl(
                    transactionDao = FakeTransactionDao(),
                    accountDao = FakeAccountDao(),
                    reminderDao = dao,
                )

            repository.dismissReminder(reminderId = 2, dismissedAt = 9_999)

            // Nothing deleted; BOTH rows of the identity group are flagged -
            // a surviving duplicate must not resurrect the card.
            assertThat(dao.rows).hasSize(2)
            assertThat(dao.rows.map { it.dismissedAt }).containsExactly(9_999L, 9_999L)
        }

    @Test
    fun `restore clears the dismissal flag on the whole group`() =
        runTest {
            val dao =
                FakeReminderDao(
                    listOf(
                        reminder(1, createdAt = 1_000).copy(dismissedAt = 5_000),
                        reminder(2, createdAt = 2_000).copy(dismissedAt = 5_000),
                    ),
                )
            val repository =
                FinanceRepositoryImpl(
                    transactionDao = FakeTransactionDao(),
                    accountDao = FakeAccountDao(),
                    reminderDao = dao,
                )

            repository.restoreReminder(reminderId = 2)

            assertThat(dao.rows.map { it.dismissedAt }).containsExactly(null, null)
        }

    @Test
    fun `delete forever removes the whole duplicate group`() =
        runTest {
            val other = reminder(9, createdAt = 500).copy(accountLast4 = "9999")
            val dao =
                FakeReminderDao(
                    listOf(reminder(1, createdAt = 1_000), reminder(2, createdAt = 2_000), other),
                )
            val repository =
                FinanceRepositoryImpl(
                    transactionDao = FakeTransactionDao(),
                    accountDao = FakeAccountDao(),
                    reminderDao = dao,
                )

            repository.deleteReminderForever(reminderId = 2)

            // Both rows of the bill's identity group are gone; the unrelated
            // card's reminder survives.
            assertThat(dao.rows.map { it.id }).containsExactly(9L)
        }

    @Test
    fun `dismissed duplicate is not resurrected by a re-parsed row`() =
        runTest {
            // The user dismissed the bill (row 1); a catch-up re-parse then
            // produced a NEW duplicate row (row 2, not flagged). The merged
            // card must stay in Older, never as a fresh active alert.
            val futureDue = System.currentTimeMillis() + 5 * 86_400_000L
            val dao =
                FakeReminderDao(
                    listOf(
                        reminder(1, createdAt = 1_000, dueDate = futureDue).copy(dismissedAt = 5_000),
                        reminder(2, createdAt = 2_000, dueDate = futureDue),
                    ),
                )
            val repository =
                FinanceRepositoryImpl(
                    transactionDao = FakeTransactionDao(),
                    accountDao = FakeAccountDao(),
                    reminderDao = dao,
                )

            val nowMs = System.currentTimeMillis()
            assertThat(repository.observeUpcomingReminders(nowMs).first()).isEmpty()
            val older = repository.observePastReminders(nowMs).first()
            assertThat(older).hasSize(1)
            assertThat(older.single().dismissedAt).isEqualTo(5_000)
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
        fromTs: Long,
        toTs: Long,
    ): List<TransactionEntity> =
        transactions.filter {
            it.amount == amount &&
                it.type == type &&
                it.accountNumber == accountNumber &&
                it.timestamp in fromTs..toTs
        }

    override suspend fun countByBankAndTail(
        bankName: String,
        accountNumber: String,
        excludeId: Long,
    ): Int =
        transactions.count {
            it.bankName == bankName && it.accountNumber == accountNumber && it.id != excludeId
        }

    override suspend fun countByAccountId(
        accountId: Long,
        excludeId: Long,
    ): Int = transactions.count { it.accountId == accountId && it.id != excludeId }

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

    override suspend fun findByBank(bankName: String): List<AccountEntity> = emptyList()

    override suspend fun findById(id: Long): AccountEntity? = null

    override suspend fun deleteById(id: Long) = Unit

    override suspend fun getAll(): List<AccountEntity> = emptyList()

    override suspend fun insert(account: AccountEntity): Long = account.id

    override suspend fun insertAll(accounts: List<AccountEntity>) = Unit

    override suspend fun update(account: AccountEntity) = Unit

    override suspend fun deleteAll() = Unit
}

private class FakeReminderDao(
    reminders: List<ReminderEntity> = emptyList(),
) : ReminderDao {
    val rows = reminders.toMutableList()

    override fun observeAll(): Flow<List<ReminderEntity>> = flowOf(rows.toList())

    override fun observeUpcoming(nowMs: Long): Flow<List<ReminderEntity>> =
        flowOf(rows.filter { it.dismissedAt == null && it.dueDate != null && it.dueDate!! >= nowMs })

    override suspend fun findByRawSmsId(rawSmsId: Long): ReminderEntity? = rows.find { it.rawSmsId == rawSmsId }

    override suspend fun getAll(): List<ReminderEntity> = rows.toList()

    override suspend fun insert(reminder: ReminderEntity): Long = reminder.id.also { rows += reminder }

    override suspend fun insertAll(reminders: List<ReminderEntity>) {
        rows += reminders
    }

    override suspend fun setDismissed(
        ids: List<Long>,
        dismissedAt: Long?,
    ) {
        rows.replaceAll { if (it.id in ids) it.copy(dismissedAt = dismissedAt) else it }
    }

    override suspend fun deleteByIds(ids: List<Long>) {
        rows.removeAll { it.id in ids }
    }

    override suspend fun deleteByRawSmsId(rawSmsId: Long) {
        rows.removeAll { it.rawSmsId == rawSmsId }
    }

    override suspend fun purgeExpired(
        dismissedCutoffMs: Long,
        dueCutoffMs: Long,
        deliveryCreatedCutoffMs: Long,
        billCreatedCutoffMs: Long,
    ): Int {
        val before = rows.size
        rows.removeAll {
            (it.dismissedAt != null && it.dismissedAt!! < dismissedCutoffMs) ||
                (it.dismissedAt == null && it.dueDate != null && it.dueDate!! < dueCutoffMs) ||
                (
                    it.dismissedAt == null &&
                        it.dueDate == null &&
                        it.type == ReminderType.DELIVERY &&
                        it.createdAt < deliveryCreatedCutoffMs
                ) ||
                (
                    it.dismissedAt == null &&
                        it.dueDate == null &&
                        it.type != ReminderType.DELIVERY &&
                        it.createdAt < billCreatedCutoffMs
                )
        }
        return before - rows.size
    }

    override suspend fun deleteAll() = rows.clear()
}
