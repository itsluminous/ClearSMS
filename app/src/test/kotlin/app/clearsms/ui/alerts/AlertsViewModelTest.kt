package app.clearsms.ui.alerts

import app.cash.turbine.test
import app.clearsms.data.db.AccountEntity
import app.clearsms.data.db.MessageEntity
import app.clearsms.data.db.ReminderDao
import app.clearsms.data.db.ReminderEntity
import app.clearsms.data.db.TransactionEntity
import app.clearsms.data.repository.FinanceRepository
import app.clearsms.domain.model.Category
import app.clearsms.domain.model.ReminderType
import app.clearsms.ui.finance.MessageLookup
import app.clearsms.ui.finance.MessageRef
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class AlertsViewModelTest {
    private val dispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun reminder(id: Long) =
        ReminderEntity(
            id = id,
            type = ReminderType.CREDIT_CARD,
            dueDate = 1_000L,
            accountLast4 = "1234",
            bankName = "IDFC FIRST",
            rawSmsId = id * 10,
            createdAt = id,
        )

    private fun viewModel(messages: Map<Long, MessageEntity> = emptyMap()): AlertsViewModel =
        AlertsViewModel(
            financeRepository = FakeFinanceRepository(past = listOf(reminder(1), reminder(2))),
            reminderDao = NoopReminderDao(),
            messageLookup = MessageLookup { id -> messages[id] },
            ioDispatcher = dispatcher,
        )

    @Test
    fun `past reminders section is collapsed by default and toggles`() =
        runTest {
            val vm = viewModel()

            vm.uiState.test {
                var state = awaitItem()
                if (!state.loaded) state = awaitItem()
                assertThat(state.pastExpanded).isFalse()
                assertThat(state.past).hasSize(2)

                vm.togglePastExpanded()
                assertThat(awaitItem().pastExpanded).isTrue()

                vm.togglePastExpanded()
                assertThat(awaitItem().pastExpanded).isFalse()
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun `source message resolves to thread and message id`() =
        runTest {
            val message =
                MessageEntity(
                    id = 10,
                    threadId = 3,
                    sender = "VM-IDFCFB",
                    normalizedSender = "IDFCFB",
                    body = "bill",
                    timestamp = 1L,
                    category = Category.IMPORTANT,
                )
            val vm = viewModel(messages = mapOf(10L to message))

            assertThat(vm.sourceMessageFor(10L)).isEqualTo(MessageRef(threadId = 3, messageId = 10))
        }

    @Test
    fun `deleted source message resolves to null`() =
        runTest {
            assertThat(viewModel().sourceMessageFor(999L)).isNull()
        }

    private fun typed(
        id: Long,
        type: ReminderType,
    ) = reminder(id).copy(type = type)

    @Test
    fun `selected pill filters both upcoming and past lists`() =
        runTest {
            val vm =
                AlertsViewModel(
                    financeRepository =
                        FakeFinanceRepository(
                            upcoming =
                                listOf(
                                    typed(1, ReminderType.INSURANCE),
                                    typed(2, ReminderType.CREDIT_CARD),
                                ),
                            past =
                                listOf(
                                    typed(3, ReminderType.INSURANCE),
                                    typed(4, ReminderType.OTHER),
                                ),
                        ),
                    reminderDao = NoopReminderDao(),
                    messageLookup = MessageLookup { null },
                    ioDispatcher = dispatcher,
                )

            vm.uiState.test {
                var state = awaitItem()
                if (!state.loaded) state = awaitItem()
                assertThat(state.upcoming).hasSize(2)
                assertThat(state.past).hasSize(2)

                vm.setFilter(AlertFilter.INSURANCE)
                state = awaitItem()
                assertThat(state.upcoming.map { it.id }).containsExactly(1L)
                assertThat(state.past.map { it.id }).containsExactly(3L)

                vm.setFilter(AlertFilter.BILL)
                state = awaitItem()
                assertThat(state.upcoming).isEmpty()
                assertThat(state.past.map { it.id }).containsExactly(4L)
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun `pill counts reflect all reminders regardless of active filter`() =
        runTest {
            val vm =
                AlertsViewModel(
                    financeRepository =
                        FakeFinanceRepository(
                            upcoming = listOf(typed(1, ReminderType.SUBSCRIPTION)),
                            past = listOf(typed(2, ReminderType.DEPOSIT), typed(3, ReminderType.DEPOSIT)),
                        ),
                    reminderDao = NoopReminderDao(),
                    messageLookup = MessageLookup { null },
                    ioDispatcher = dispatcher,
                )

            vm.uiState.test {
                var state = awaitItem()
                if (!state.loaded) state = awaitItem()
                assertThat(state.counts[AlertFilter.ALL]).isEqualTo(3)
                assertThat(state.counts[AlertFilter.SUBSCRIPTION]).isEqualTo(1)
                assertThat(state.counts[AlertFilter.DEPOSIT]).isEqualTo(2)

                vm.setFilter(AlertFilter.DEPOSIT)
                state = awaitItem()
                // Counts stay global so the badges do not collapse to the
                // filtered subset.
                assertThat(state.counts[AlertFilter.ALL]).isEqualTo(3)
                assertThat(state.counts[AlertFilter.SUBSCRIPTION]).isEqualTo(1)
                cancelAndIgnoreRemainingEvents()
            }
        }
}

private class FakeFinanceRepository(
    private val upcoming: List<ReminderEntity> = emptyList(),
    private val past: List<ReminderEntity> = emptyList(),
) : FinanceRepository {
    override fun observeTransactions(): Flow<List<TransactionEntity>> = flowOf(emptyList())

    override fun observeLatestTransactions(limit: Int): Flow<List<TransactionEntity>> = flowOf(emptyList())

    override fun observeTransactionsByAccount(accountNumber: String): Flow<List<TransactionEntity>> = flowOf(emptyList())

    override fun observeTransactionsByAccount(
        accountNumber: String,
        limit: Int,
    ): Flow<List<TransactionEntity>> = flowOf(emptyList())

    override suspend fun latestTransactionForAccount(
        accountNumber: String,
        bankName: String,
    ): TransactionEntity? = null

    override fun observeAccounts(): Flow<List<AccountEntity>> = flowOf(emptyList())

    override fun observeReminders(): Flow<List<ReminderEntity>> = flowOf(upcoming)

    override fun observeUpcomingReminders(nowMs: Long): Flow<List<ReminderEntity>> = flowOf(upcoming)

    override fun observePastReminders(nowMs: Long): Flow<List<ReminderEntity>> = flowOf(past)

    override suspend fun setCardLimit(
        accountId: Long,
        limit: Double?,
    ) = Unit

    override suspend fun addNote(
        transactionId: Long,
        note: String?,
    ) = Unit
}

private class NoopReminderDao : ReminderDao {
    override fun observeUpcoming(nowMs: Long): Flow<List<ReminderEntity>> = flowOf(emptyList())

    override fun observePast(nowMs: Long): Flow<List<ReminderEntity>> = flowOf(emptyList())

    override suspend fun findByRawSmsId(rawSmsId: Long): ReminderEntity? = null

    override suspend fun getAll(): List<ReminderEntity> = emptyList()

    override suspend fun insert(reminder: ReminderEntity): Long = reminder.id

    override suspend fun insertAll(reminders: List<ReminderEntity>) = Unit

    override suspend fun deleteById(id: Long) = Unit

    override suspend fun deleteByRawSmsId(rawSmsId: Long) = Unit

    override suspend fun deleteAll() = Unit
}
