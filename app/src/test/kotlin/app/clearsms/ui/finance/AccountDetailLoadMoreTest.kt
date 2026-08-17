package app.clearsms.ui.finance

import androidx.lifecycle.SavedStateHandle
import app.clearsms.data.db.AccountEntity
import app.clearsms.data.db.ReminderEntity
import app.clearsms.data.db.TransactionEntity
import app.clearsms.data.repository.FinanceRepository
import app.clearsms.domain.model.TransactionType
import app.clearsms.testing.FakeSettingsRepository
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test

/**
 * Regression: with a Credit/Debit filter active, Load more must terminate.
 * The spinner-stop condition used to compare the FILTERED on-screen count
 * against the UNFILTERED page limit, so under a filter it never satisfied
 * and the button span forever.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AccountDetailLoadMoreTest {
    private val dispatcher = StandardTestDispatcher()
    private lateinit var repository: FakeAccountFinanceRepository

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        repository = FakeAccountFinanceRepository()
        // 90 transactions on the account, only 3 of them credits: any credit
        // filter shows far fewer rows than the page limit can ever reach.
        val seeded = mutableListOf<TransactionEntity>()
        repeat(90) { i ->
            seeded.add(
                TransactionEntity(
                    id = i + 1L,
                    accountNumber = "4321",
                    bankName = "HDFC Bank",
                    amount = 100.0 + i,
                    type = if (i % 30 == 0) TransactionType.CREDIT else TransactionType.DEBIT,
                    merchantName = "m$i",
                    timestamp = 1_700_000_000_000L + i * 86_400_000L,
                    rawSmsId = i + 1L,
                ),
            )
        }
        repository.transactions.value = seeded
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun viewModel() =
        AccountDetailViewModel(
            savedStateHandle = SavedStateHandle(mapOf("accountNumber" to "4321", "bank" to "HDFC Bank")),
            financeRepository = repository,
            settingsRepository = FakeSettingsRepository(),
            messageLookup = { null },
            balanceVisibility = BalanceVisibility(),
            ioDispatcher = dispatcher,
        )

    @Test
    fun `load more terminates under a credit filter`() =
        runTest(dispatcher) {
            val vm = viewModel()
            val collector = launch { vm.uiState.collect {} }

            vm.uiState.first { it.loaded }
            vm.setFilter(TxFilter.CREDITED)
            vm.loadMore()

            // The next page arrives (unfiltered loadedCount grows to 60);
            // the spinner MUST reset even though only ~2-3 credit rows show.
            val settled = vm.uiState.first { it.loadedCount >= 60 }
            assertThat(settled.isLoadingMore).isFalse()
            assertThat(settled.groups.sumOf { it.transactions.size }).isLessThan(10)

            collector.cancel()
        }

    @Test
    fun `load more still terminates unfiltered`() =
        runTest(dispatcher) {
            val vm = viewModel()
            val collector = launch { vm.uiState.collect {} }

            vm.uiState.first { it.loaded }
            vm.loadMore()
            val settled = vm.uiState.first { it.loadedCount >= 60 }
            assertThat(settled.isLoadingMore).isFalse()

            collector.cancel()
        }
}

private class FakeAccountFinanceRepository : FinanceRepository {
    val transactions = MutableStateFlow<List<TransactionEntity>>(emptyList())
    val accounts = MutableStateFlow<List<AccountEntity>>(emptyList())

    override fun observeTransactions(): Flow<List<TransactionEntity>> = transactions

    override fun observeLatestTransactions(limit: Int): Flow<List<TransactionEntity>> = transactions

    override fun observeTransactionsByAccount(
        accountNumber: String,
        bankName: String,
    ): Flow<List<TransactionEntity>> = transactions

    override fun observeTransactionsByAccount(
        accountNumber: String,
        bankName: String,
        limit: Int,
    ): Flow<List<TransactionEntity>> = transactions.map { it.take(limit) }

    override suspend fun latestTransactionForAccount(
        accountNumber: String,
        bankName: String,
    ): TransactionEntity? = null

    override fun observeAccounts(): Flow<List<AccountEntity>> = accounts

    override fun observeReminders(): Flow<List<ReminderEntity>> = MutableStateFlow(emptyList())

    override fun observeUpcomingReminders(nowMs: Long): Flow<List<ReminderEntity>> = MutableStateFlow(emptyList())

    override fun observePastReminders(nowMs: Long): Flow<List<ReminderEntity>> = MutableStateFlow(emptyList())

    override suspend fun dismissReminder(
        reminderId: Long,
        dismissedAt: Long,
    ) = Unit

    override suspend fun restoreReminder(reminderId: Long) = Unit

    override suspend fun deleteReminderForever(reminderId: Long) = Unit

    override suspend fun clearOlderReminders(nowMs: Long): Int = 0

    override suspend fun addNote(
        transactionId: Long,
        note: String?,
    ) = Unit
}
