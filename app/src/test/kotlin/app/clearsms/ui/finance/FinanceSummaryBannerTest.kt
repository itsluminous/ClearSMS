package app.clearsms.ui.finance

import app.clearsms.data.db.AccountEntity
import app.clearsms.data.db.ReminderEntity
import app.clearsms.data.db.TransactionEntity
import app.clearsms.data.prefs.SettingsRepository
import app.clearsms.data.repository.FinanceRepository
import app.clearsms.domain.model.AccountType
import app.clearsms.domain.model.Category
import app.clearsms.domain.model.FinanceTab
import app.clearsms.domain.model.LogoBackground
import app.clearsms.domain.model.MerchantCategory
import app.clearsms.domain.model.NotificationAction
import app.clearsms.domain.model.OtpAutoDeletePolicy
import app.clearsms.domain.model.OtpDisplaySize
import app.clearsms.domain.model.StartDestination
import app.clearsms.domain.model.SwipeAction
import app.clearsms.domain.model.ThemeMode
import app.clearsms.domain.model.TransactionType
import app.clearsms.ui.alerts.AlertFilter
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
import java.time.ZonedDateTime

/**
 * The finance summary banner's click behaviour: tapping toggles the inline
 * breakdown, and — because expansion is a disclosure, not a navigation — the
 * persisted pill selection is never disturbed.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class FinanceSummaryBannerTest {
    private val dispatcher = UnconfinedTestDispatcher()
    private lateinit var settings: FakeSettingsRepository
    private lateinit var finance: FakeFinanceRepository

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        settings = FakeSettingsRepository()
        finance = FakeFinanceRepository()
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun viewModel() =
        FinanceViewModel(
            financeRepository = finance,
            settingsRepository = settings,
            messageLookup = { null },
            balanceVisibility = BalanceVisibility(),
            ioDispatcher = dispatcher,
        )

    @Test
    fun `tapping the banner expands the breakdown and tapping again collapses it`() {
        val vm = viewModel()
        assertThat(vm.summaryExpanded.value).isFalse()

        vm.toggleSummaryBreakdown()
        assertThat(vm.summaryExpanded.value).isTrue()

        vm.toggleSummaryBreakdown()
        assertThat(vm.summaryExpanded.value).isFalse()
    }

    @Test
    fun `expanding the banner never changes the persisted pill selection`() =
        runTest(dispatcher) {
            settings.financeTabFlow.value = FinanceTab.CREDIT_CARDS
            val vm = viewModel()
            val tabs = mutableListOf<FinanceTab>()
            val job = launch { vm.selectedTab.collect { tabs += it } }

            vm.toggleSummaryBreakdown()
            vm.toggleSummaryBreakdown()

            assertThat(vm.selectedTab.value).isEqualTo(FinanceTab.CREDIT_CARDS)
            // The DataStore-backed tab was never written to either.
            assertThat(settings.financeTabFlow.value).isEqualTo(FinanceTab.CREDIT_CARDS)
            // Beyond the stateIn default settling to the persisted value, the
            // toggles caused no further tab emissions.
            assertThat(tabs.distinct()).containsExactly(FinanceTab.ACCOUNTS, FinanceTab.CREDIT_CARDS).inOrder()
            job.cancel()
        }

    @Test
    fun `ui state carries the month breakdown the expanded banner shows`() =
        runTest(dispatcher) {
            val now = ZonedDateTime.now()
            val thisMonth = now.toInstant().toEpochMilli()
            val lastYear = now.minusMonths(13).toInstant().toEpochMilli()
            finance.transactions.value =
                listOf(
                    tx(1, 500.0, TransactionType.DEBIT, thisMonth),
                    tx(2, 300.0, TransactionType.DEBIT, thisMonth),
                    tx(3, 1_000.0, TransactionType.CREDIT, thisMonth),
                    tx(4, 9_999.0, TransactionType.DEBIT, lastYear),
                )
            val vm = viewModel()
            val job = launch { vm.uiState.collect {} }

            val state = vm.uiState.value
            assertThat(state.monthTxCount).isEqualTo(3)
            assertThat(state.monthDebitCount).isEqualTo(2)
            assertThat(state.monthCreditCount).isEqualTo(1)
            assertThat(state.monthDebits).isEqualTo(800.0)
            assertThat(state.monthCredits).isEqualTo(1_000.0)
            assertThat(state.monthNet).isEqualTo(200.0)
            job.cancel()
        }

    @Test
    fun `summary totals exclude self-transfers and card-bill payments but keep real activity`() =
        runTest(dispatcher) {
            val thisMonth = ZonedDateTime.now().toInstant().toEpochMilli()
            finance.accounts.value =
                listOf(
                    AccountEntity(
                        accountNumber = "5106",
                        bankName = "Axis Bank",
                        type = AccountType.CREDIT_CARD,
                        lastUpdated = thisMonth,
                    ),
                )
            finance.transactions.value =
                listOf(
                    // Real spend + income — counted:
                    tx(1, 500.0, TransactionType.DEBIT, thisMonth),
                    tx(2, 1_000.0, TransactionType.CREDIT, thisMonth),
                    // Bank-side card-bill transfer — excluded:
                    tx(3, 700.0, TransactionType.DEBIT, thisMonth, category = MerchantCategory.TRANSFER),
                    // Card-side "payment received" for the same rupees —
                    // excluded because it carries the card's full identity
                    // (last-4 AND bank), not the tail alone:
                    tx(4, 700.0, TransactionType.CREDIT, thisMonth, account = "5106", bank = "Axis Bank"),
                )
            val vm = viewModel()
            val job = launch { vm.uiState.collect {} }

            val state = vm.uiState.value
            assertThat(state.monthDebits).isEqualTo(500.0)
            assertThat(state.monthCredits).isEqualTo(1_000.0)
            assertThat(state.monthNet).isEqualTo(500.0)
            assertThat(state.monthTxCount).isEqualTo(2)
            assertThat(state.monthExcludedCount).isEqualTo(2)
            assertThat(state.monthExcludedTotal).isEqualTo(1_400.0)
            job.cancel()
        }

    private fun tx(
        id: Long,
        amount: Double,
        type: TransactionType,
        timestamp: Long,
        account: String = "1234",
        bank: String = "Bank",
        category: MerchantCategory = MerchantCategory.OTHER,
    ) = TransactionEntity(
        id = id,
        amount = amount,
        type = type,
        accountNumber = account,
        bankName = bank,
        timestamp = timestamp,
        category = category,
        rawSmsId = id,
    )
}

private class FakeFinanceRepository : FinanceRepository {
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
    ): Flow<List<TransactionEntity>> = transactions

    override suspend fun latestTransactionForAccount(
        accountNumber: String,
        bankName: String,
    ): TransactionEntity? = null

    override fun observeAccounts(): Flow<List<AccountEntity>> = accounts

    override fun observeReminders(): Flow<List<ReminderEntity>> = MutableStateFlow(emptyList())

    override fun observeUpcomingReminders(nowMs: Long): Flow<List<ReminderEntity>> = MutableStateFlow(emptyList())

    override fun observePastReminders(nowMs: Long): Flow<List<ReminderEntity>> = MutableStateFlow(emptyList())

    override suspend fun addNote(
        transactionId: Long,
        note: String?,
    ) = Unit
}

private class FakeSettingsRepository : SettingsRepository {
    val financeTabFlow = MutableStateFlow(FinanceTab.ACCOUNTS)

    override val theme = MutableStateFlow(ThemeMode.SYSTEM)

    override suspend fun setTheme(value: ThemeMode) = Unit

    override val otpAutoCopy = MutableStateFlow(true)

    override suspend fun setOtpAutoCopy(value: Boolean) = Unit

    override val otpAutoDeletePolicy = MutableStateFlow(OtpAutoDeletePolicy.NEVER)

    override suspend fun setOtpAutoDeletePolicy(value: OtpAutoDeletePolicy) = Unit

    override val otpDisplaySize = MutableStateFlow(OtpDisplaySize.DEFAULT)

    override suspend fun setOtpDisplaySize(value: OtpDisplaySize) = Unit

    override val showTransactionDetails = MutableStateFlow(true)

    override suspend fun setShowTransactionDetails(value: Boolean) = Unit

    override val showBalance = MutableStateFlow(true)

    override suspend fun setShowBalance(value: Boolean) = Unit

    override val signature = MutableStateFlow("")

    override suspend fun setSignature(value: String) = Unit

    override val onboardingComplete = MutableStateFlow(true)

    override suspend fun setOnboardingComplete(value: Boolean) = Unit

    override val showRichAvatars = MutableStateFlow(true)

    override suspend fun setShowRichAvatars(value: Boolean) = Unit

    override val notificationActions = MutableStateFlow(emptySet<NotificationAction>())

    override suspend fun setNotificationActions(value: Set<NotificationAction>) = Unit

    override val swipeActionStart = MutableStateFlow(SwipeAction.ARCHIVE)

    override suspend fun setSwipeActionStart(value: SwipeAction) = Unit

    override val swipeActionEnd = MutableStateFlow(SwipeAction.DELETE)

    override suspend fun setSwipeActionEnd(value: SwipeAction) = Unit

    override val defaultDestination = MutableStateFlow(StartDestination.INBOX)

    override suspend fun setDefaultDestination(value: StartDestination) = Unit

    override val defaultInboxFilter = MutableStateFlow<Category?>(null)

    override suspend fun setDefaultInboxFilter(value: Category?) = Unit

    override val financeTab: Flow<FinanceTab> = financeTabFlow

    override suspend fun setFinanceTab(value: FinanceTab) {
        financeTabFlow.value = value
    }

    override val transactionNotifications = MutableStateFlow(true)

    override suspend fun setTransactionNotifications(value: Boolean) = Unit

    override val logoBackground = MutableStateFlow(LogoBackground.WHITE)

    override suspend fun setLogoBackground(value: LogoBackground) = Unit

    override val inboxPillOrder = MutableStateFlow(Category.entries.toList())

    override suspend fun setInboxPillOrder(value: List<Category>) = Unit

    override val financePillOrder = MutableStateFlow(FinanceTab.entries.toList())

    override suspend fun setFinancePillOrder(value: List<FinanceTab>) = Unit

    override val alertsPillOrder = MutableStateFlow(AlertFilter.entries.toList())

    override suspend fun setAlertsPillOrder(value: List<AlertFilter>) = Unit

    override val handledOtpMessageId = MutableStateFlow(0L)

    override suspend fun setHandledOtpMessageId(value: Long) = Unit
}
