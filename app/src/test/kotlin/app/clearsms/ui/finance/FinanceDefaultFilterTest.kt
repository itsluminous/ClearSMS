package app.clearsms.ui.finance

import app.clearsms.data.db.AccountEntity
import app.clearsms.data.db.ReminderEntity
import app.clearsms.data.db.TransactionEntity
import app.clearsms.data.repository.FinanceRepository
import app.clearsms.domain.model.FinanceTab
import app.clearsms.testing.FakeSettingsRepository
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test

/**
 * Settings → Finance → Default Finance filter: the Finance screen opens on
 * the chosen pill, and tapping a pill only overrides the session - it never
 * rewrites the stored default.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class FinanceDefaultFilterTest {
    private val dispatcher = UnconfinedTestDispatcher()
    private lateinit var settings: FakeSettingsRepository

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        settings = FakeSettingsRepository()
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun viewModel() =
        FinanceViewModel(
            financeRepository = NoDataFinanceRepository(),
            settingsRepository = settings,
            messageLookup = { null },
            balanceVisibility = BalanceVisibility(),
            ioDispatcher = dispatcher,
        )

    @Test
    fun `finance screen opens on the chosen default filter`() =
        runTest(dispatcher) {
            settings.defaultFinanceFilter.value = FinanceTab.TRANSACTIONS
            val vm = viewModel()
            val job = launch { vm.selectedTab.collect {} }

            assertThat(vm.selectedTab.value).isEqualTo(FinanceTab.TRANSACTIONS)
            job.cancel()
        }

    @Test
    fun `tapping a pill overrides the session tab without touching the stored default`() =
        runTest(dispatcher) {
            val vm = viewModel()
            val job = launch { vm.selectedTab.collect {} }

            vm.setTab(FinanceTab.RECHARGES)

            assertThat(vm.selectedTab.value).isEqualTo(FinanceTab.RECHARGES)
            assertThat(settings.defaultFinanceFilter.value).isEqualTo(FinanceTab.ACCOUNTS)
            job.cancel()
        }

    @Test
    fun `changing the default in settings round-trips into a fresh finance screen`() =
        runTest(dispatcher) {
            settings.setDefaultFinanceFilter(FinanceTab.CREDIT_CARDS)
            val vm = viewModel()
            val job = launch { vm.selectedTab.collect {} }

            assertThat(vm.selectedTab.value).isEqualTo(FinanceTab.CREDIT_CARDS)
            job.cancel()
        }
}

private class NoDataFinanceRepository : FinanceRepository {
    override fun observeTransactions(): Flow<List<TransactionEntity>> = MutableStateFlow(emptyList())

    override fun observeLatestTransactions(limit: Int): Flow<List<TransactionEntity>> = MutableStateFlow(emptyList())

    override fun observeTransactionsByAccount(
        accountNumber: String,
        bankName: String,
    ): Flow<List<TransactionEntity>> = MutableStateFlow(emptyList())

    override fun observeTransactionsByAccount(
        accountNumber: String,
        bankName: String,
        limit: Int,
    ): Flow<List<TransactionEntity>> = MutableStateFlow(emptyList())

    override suspend fun latestTransactionForAccount(
        accountNumber: String,
        bankName: String,
    ): TransactionEntity? = null

    override fun observeAccounts(): Flow<List<AccountEntity>> = MutableStateFlow(emptyList())

    override fun observeReminders(): Flow<List<ReminderEntity>> = MutableStateFlow(emptyList())

    override fun observeUpcomingReminders(nowMs: Long): Flow<List<ReminderEntity>> = MutableStateFlow(emptyList())

    override fun observePastReminders(nowMs: Long): Flow<List<ReminderEntity>> = MutableStateFlow(emptyList())

    override suspend fun addNote(
        transactionId: Long,
        note: String?,
    ) = Unit
}
