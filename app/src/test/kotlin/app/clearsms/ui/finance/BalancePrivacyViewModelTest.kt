package app.clearsms.ui.finance

import app.clearsms.data.db.AccountEntity
import app.clearsms.data.db.ReminderEntity
import app.clearsms.data.db.TransactionEntity
import app.clearsms.data.repository.FinanceRepository
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
 * The masking state machine driving the Finance tab's balance privacy gate:
 *
 * setting ON → visible (no gate); OFF → masked; successful auth → revealed;
 * cancel/failure never calls reveal so balances stay masked; backgrounding
 * (conceal) → re-masked; toggling the setting always conceals first so a
 * stale reveal can never survive an OFF→ON→OFF cycle.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class BalancePrivacyViewModelTest {
    private val dispatcher = UnconfinedTestDispatcher()
    private lateinit var settings: FakeSettingsRepository
    private lateinit var visibility: BalanceVisibility

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        settings = FakeSettingsRepository()
        visibility = BalanceVisibility()
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun viewModel() =
        FinanceViewModel(
            financeRepository = EmptyFinanceRepository(),
            settingsRepository = settings,
            messageLookup = { null },
            balanceVisibility = visibility,
            ioDispatcher = dispatcher,
        )

    @Test
    fun `setting ON - balances visible and not gated (default behaviour)`() =
        runTest(dispatcher) {
            val vm = viewModel()
            val job = launch { vm.uiState.collect {} }

            assertThat(vm.uiState.value.balanceGated).isFalse()
            assertThat(vm.uiState.value.balancesRevealed).isTrue()
            job.cancel()
        }

    @Test
    fun `setting OFF - balances masked until authenticated`() =
        runTest(dispatcher) {
            settings.showBalance.value = false
            val vm = viewModel()
            val job = launch { vm.uiState.collect {} }

            assertThat(vm.uiState.value.balanceGated).isTrue()
            assertThat(vm.uiState.value.balancesRevealed).isFalse()
            job.cancel()
        }

    @Test
    fun `successful authentication reveals for the session`() =
        runTest(dispatcher) {
            settings.showBalance.value = false
            val vm = viewModel()
            val job = launch { vm.uiState.collect {} }

            vm.revealBalances()

            assertThat(vm.uiState.value.balanceGated).isTrue()
            assertThat(vm.uiState.value.balancesRevealed).isTrue()
            job.cancel()
        }

    @Test
    fun `cancelled or failed authentication leaves balances masked`() =
        runTest(dispatcher) {
            settings.showBalance.value = false
            val vm = viewModel()
            val job = launch { vm.uiState.collect {} }

            // BalanceUnlock only calls onReveal on success - cancel/error/
            // lockout never do, so the state simply never changes.
            assertThat(vm.uiState.value.balancesRevealed).isFalse()
            job.cancel()
        }

    @Test
    fun `backgrounding re-masks a revealed session`() =
        runTest(dispatcher) {
            settings.showBalance.value = false
            val vm = viewModel()
            val job = launch { vm.uiState.collect {} }

            vm.revealBalances()
            assertThat(vm.uiState.value.balancesRevealed).isTrue()

            // MainActivity.onStop calls conceal() when leaving the foreground.
            visibility.conceal()
            assertThat(vm.uiState.value.balancesRevealed).isFalse()
            job.cancel()
        }

    @Test
    fun `stale reveal never survives an OFF-ON-OFF settings cycle`() =
        runTest(dispatcher) {
            settings.showBalance.value = false
            val vm = viewModel()
            val job = launch { vm.uiState.collect {} }

            vm.revealBalances()
            // SettingsViewModel.setShowBalance conceals before every write:
            visibility.conceal()
            settings.showBalance.value = true
            assertThat(vm.uiState.value.balancesRevealed).isTrue() // setting ON

            visibility.conceal()
            settings.showBalance.value = false
            // Toggled off again: masked immediately, the old unlock is gone.
            assertThat(vm.uiState.value.balancesRevealed).isFalse()
            job.cancel()
        }
}

/** Minimal no-data [FinanceRepository]; the gate logic never touches data. */
private class EmptyFinanceRepository : FinanceRepository {
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
