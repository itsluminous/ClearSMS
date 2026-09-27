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
 * Finance pill visibility, through the shared [app.clearsms.ui.navigation.PillConfig]
 * mechanism: the row renders the visible tabs in the user's order, a hidden
 * tab can never stay selected (default filter or session tap), the fallback
 * is the first visible tab, and all hidden means no row and no section.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class FinancePillVisibilityTest {
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
            financeRepository = NoPillsFinanceRepository(),
            settingsRepository = settings,
            messageLookup = { null },
            balanceVisibility = BalanceVisibility(),
            ioDispatcher = dispatcher,
        )

    @Test
    fun `the row shows the visible tabs in the configured order`() =
        runTest(dispatcher) {
            settings.financePillOrder.value = listOf(FinanceTab.RECHARGES, FinanceTab.ACCOUNTS)
            settings.financeHiddenPills.value = setOf(FinanceTab.CREDIT_CARDS)
            val vm = viewModel()
            val job = launch { vm.uiState.collect {} }

            val pills = vm.uiState.value.pills
            assertThat(pills.visible)
                .containsExactly(FinanceTab.RECHARGES, FinanceTab.ACCOUNTS, FinanceTab.TRANSACTIONS)
                .inOrder()
            assertThat(pills.ordered).hasSize(FinanceTab.entries.size)
            assertThat(pills.showsRow).isTrue()
            job.cancel()
        }

    @Test
    fun `a default filter pointing at a hidden tab opens on the first visible tab instead`() =
        runTest(dispatcher) {
            settings.defaultFinanceFilter.value = FinanceTab.ACCOUNTS
            settings.financeHiddenPills.value = setOf(FinanceTab.ACCOUNTS)
            val vm = viewModel()
            val job = launch { vm.selectedTab.collect {} }

            assertThat(vm.selectedTab.value).isEqualTo(FinanceTab.CREDIT_CARDS)
            job.cancel()
        }

    @Test
    fun `hiding the open tab falls back, and un-hiding it restores the selection`() =
        runTest(dispatcher) {
            val vm = viewModel()
            val job = launch { vm.selectedTab.collect {} }
            vm.setTab(FinanceTab.TRANSACTIONS)
            assertThat(vm.selectedTab.value).isEqualTo(FinanceTab.TRANSACTIONS)

            settings.setFinanceHiddenPills(setOf(FinanceTab.TRANSACTIONS))
            assertThat(vm.selectedTab.value).isEqualTo(FinanceTab.ACCOUNTS)

            settings.setFinanceHiddenPills(emptySet())
            assertThat(vm.selectedTab.value).isEqualTo(FinanceTab.TRANSACTIONS)
            // Never written back: the stored default is untouched throughout.
            assertThat(settings.defaultFinanceFilter.value).isEqualTo(FinanceTab.ACCOUNTS)
            job.cancel()
        }

    @Test
    fun `all tabs hidden - no row and no section, the screen is the month summary alone`() =
        runTest(dispatcher) {
            settings.financeHiddenPills.value = FinanceTab.entries.toSet()
            val vm = viewModel()
            val job = launch { vm.selectedTab.collect {} }
            val stateJob = launch { vm.uiState.collect {} }

            assertThat(vm.selectedTab.value).isNull()
            assertThat(vm.uiState.value.pills.showsRow).isFalse()
            assertThat(vm.uiState.value.pills.visible).isEmpty()
            job.cancel()
            stateJob.cancel()
        }

    @Test
    fun `for every hidden combination the selected tab is visible or there is no tab`() =
        runTest(dispatcher) {
            val all = FinanceTab.entries.toList()
            for (mask in 0 until (1 shl all.size)) {
                val hidden = all.filterIndexed { index, _ -> mask and (1 shl index) != 0 }.toSet()
                settings.financeHiddenPills.value = hidden
                for (tab in all) {
                    val vm = viewModel()
                    val job = launch { vm.selectedTab.collect {} }
                    vm.setTab(tab)
                    val selected = vm.selectedTab.value
                    if (hidden.size == all.size) {
                        assertThat(selected).isNull()
                    } else {
                        assertThat(selected).isNotNull()
                        assertThat(selected).isNotIn(hidden)
                        if (tab !in hidden) assertThat(selected).isEqualTo(tab)
                    }
                    job.cancel()
                }
            }
        }
}

private class NoPillsFinanceRepository : FinanceRepository {
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
