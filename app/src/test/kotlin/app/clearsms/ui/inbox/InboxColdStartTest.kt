package app.clearsms.ui.inbox

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.work.WorkManager
import androidx.work.testing.WorkManagerTestInitHelper
import app.cash.turbine.test
import app.clearsms.data.db.CategoryUnreadCount
import app.clearsms.data.db.ClearSmsDatabase
import app.clearsms.data.prefs.SettingsRepository
import app.clearsms.data.repository.SenderBlocker
import app.clearsms.data.repository.SenderMuter
import app.clearsms.data.repository.UndoManager
import app.clearsms.domain.categorizer.SenderIdLookup
import app.clearsms.domain.model.Category
import app.clearsms.domain.model.InboxPill
import app.clearsms.domain.model.MessageSortOrder
import app.clearsms.sms.ContactsSource
import app.clearsms.testing.FakeMessageRepository
import app.clearsms.testing.FakeSettingsRepository
import app.clearsms.testing.InMemoryPreferencesDataStore
import app.clearsms.work.CatchUpSyncScheduler
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

/**
 * Issue #63: on a cold start the inbox rendered the DEFAULT pill set, a
 * countless Unread switch and an unfiltered query before the first
 * DataStore read, then corrected itself - the reporter customised his pills
 * and watched the built-in ones flash on every launch.
 *
 * The mechanism is the state's seed: `stateIn(..., InboxUiState())` gives
 * the screen a default-constructed state until the combine's first
 * emission, and every preference-shaped field of that default is Kotlin's
 * value, not the user's. The fix is the repo's existing shape - a `loaded`
 * flag, as on `ConversationUiState` - false ONLY on that seed, and the
 * screen gates everything preference-derived on it.
 *
 * The DataStore stand-ins below are UNSEEDED replay flows: nothing arrives
 * until the test says the read completed, which is exactly the window the
 * reporter's first frame lives in. Everything else is the pager test's
 * harness. Synthetic messages only.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class InboxColdStartTest {
    private val dispatcher = StandardTestDispatcher()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    private lateinit var context: Context
    private lateinit var db: ClearSmsDatabase
    private lateinit var repository: FakeMessageRepository
    private lateinit var settings: FakeSettingsRepository

    /** The four inbox preferences the flash was made of - NOT read yet. */
    private val pillOrders = MutableSharedFlow<List<InboxPill>>(replay = 1)
    private val hiddenPills = MutableSharedFlow<Set<InboxPill>>(replay = 1)
    private val unreadToggles = MutableSharedFlow<Boolean>(replay = 1)
    private val defaultFilters = MutableSharedFlow<Category?>(replay = 1)

    /** The reporter's configuration: Personal and OTP only, no Unread switch, opens on Personal. */
    private val configuredOrder =
        listOf(InboxPill.PERSONAL, InboxPill.OTP) + InboxPill.entries.filterNot { it == InboxPill.PERSONAL || it == InboxPill.OTP }
    private val configuredHidden = InboxPill.entries.filterNot { it == InboxPill.PERSONAL || it == InboxPill.OTP }.toSet()

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        context = ApplicationProvider.getApplicationContext()
        WorkManagerTestInitHelper.initializeTestWorkManager(context)
        db = Room.inMemoryDatabaseBuilder(context, ClearSmsDatabase::class.java).allowMainThreadQueries().build()
        repository = FakeMessageRepository()
        repository.unreadCounts.value = listOf(CategoryUnreadCount(Category.PERSONAL, 2), CategoryUnreadCount(Category.OTP, 1))
        settings = FakeSettingsRepository()
    }

    @After
    fun tearDown() {
        WorkManager
            .getInstance(context)
            .cancelAllWork()
            .result
            .get()
        scope.cancel()
        db.close()
        Dispatchers.resetMain()
    }

    private fun viewModel(): InboxViewModel {
        val dataStoreLike =
            object : SettingsRepository by settings {
                override val inboxPillOrder: Flow<List<InboxPill>> = pillOrders
                override val inboxHiddenPills: Flow<Set<InboxPill>> = hiddenPills
                override val inboxUnreadToggle: Flow<Boolean> = unreadToggles
                override val defaultInboxFilter: Flow<Category?> = defaultFilters
            }
        val senderMuter = SenderMuter(settings)
        return InboxViewModel(
            context = context,
            messageRepository = repository,
            undoManager = UndoManager(repository, scope, { true }),
            senderBlocker = SenderBlocker(settings, repository, senderMuter, InMemoryPreferencesDataStore(), scope),
            senderMuter = senderMuter,
            senderIdLookup = SenderIdLookup { null },
            contactsSource = ContactsSource(context),
            settings = dataStoreLike,
            catchUpSyncScheduler = CatchUpSyncScheduler(context, db.messageDao(), WorkManager.getInstance(context), dispatcher),
            workManager = WorkManager.getInstance(context),
            ioDispatcher = dispatcher,
        )
    }

    /** The DataStore read completes: one snapshot, every preference at once. */
    private suspend fun settingsArrive(
        order: List<InboxPill> = configuredOrder,
        hidden: Set<InboxPill> = configuredHidden,
        unreadToggle: Boolean = false,
        defaultFilter: Category? = Category.PERSONAL,
    ) {
        pillOrders.emit(order)
        hiddenPills.emit(hidden)
        unreadToggles.emit(unreadToggle)
        defaultFilters.emit(defaultFilter)
    }

    // ------------------------------------------------------------------
    // The mechanism, and the flag that names it.
    // ------------------------------------------------------------------

    @Test
    fun `the seed state is not loaded - and it IS the default pill set the reporter saw`() {
        val seed = InboxUiState()
        assertThat(seed.loaded).isFalse()
        // Frame 1 exactly: every built-in pill, Unread switch on, no count.
        assertThat(seed.pills.visible).isEqualTo(InboxPill.entries.toList())
        assertThat(seed.showUnreadToggle).isTrue()
        assertThat(seed.totalUnread).isEqualTo(0)
        assertThat(seed.filter).isEqualTo(InboxFilterState())
    }

    @Test
    fun `before the settings are read the state is the unloaded seed and nothing configured leaks out`() =
        runTest(dispatcher) {
            val viewModel = viewModel()
            viewModel.uiState.test {
                advanceUntilIdle()
                val seed = awaitItem()
                assertThat(seed.loaded).isFalse()
                // Counts are already in the repository, but no state carries
                // them until the preferences have arrived too: the combine
                // cannot emit on a partial read.
                expectNoEvents()

                settingsArrive()
                advanceUntilIdle()

                val loaded = awaitItem()
                assertThat(loaded.loaded).isTrue()
                assertThat(loaded.pills.visible).containsExactly(InboxPill.PERSONAL, InboxPill.OTP).inOrder()
                assertThat(loaded.showUnreadToggle).isFalse()
                assertThat(loaded.totalUnread).isEqualTo(3)
                assertThat(loaded.filter.pill).isEqualTo(InboxPill.PERSONAL)
                // ONE step from seed to the user's values - no intermediate
                // frame with the defaults marked loaded.
                expectNoEvents()
            }
        }

    @Test
    fun `no loaded state ever carries the built-in pill set when the user configured another`() =
        runTest(dispatcher) {
            val viewModel = viewModel()
            val seen = mutableListOf<InboxUiState>()
            viewModel.uiState.test {
                advanceUntilIdle()
                seen += awaitItem()
                settingsArrive()
                advanceUntilIdle()
                seen += awaitItem()
                // A later unrelated write re-emits the same snapshot; still the user's.
                settingsArrive()
                advanceUntilIdle()
                expectNoEvents()
            }
            seen.filter { it.loaded }.forEach { state ->
                assertThat(state.pills.visible).isNotEqualTo(InboxPill.entries.toList())
                assertThat(state.pills.visible).containsExactly(InboxPill.PERSONAL, InboxPill.OTP).inOrder()
            }
            assertThat(seen.filter { it.loaded }).isNotEmpty()
        }

    @Test
    fun `the default inbox filter is the FIRST query - never an unfiltered All that then flips`() =
        runTest(dispatcher) {
            val viewModel = viewModel()
            viewModel.pagerKeys.test {
                advanceUntilIdle()
                // Nothing to query until the default filter preference is read.
                expectNoEvents()
                settingsArrive(defaultFilter = Category.PERSONAL)
                advanceUntilIdle()
                assertThat(awaitItem()).isEqualTo(InboxPagerKey(Category.PERSONAL, false, MessageSortOrder.RECEIVED))
                expectNoEvents()
            }
        }

    @Test
    fun `the settled state is unchanged - loaded is the only field the fix adds`() =
        runTest(dispatcher) {
            val viewModel = viewModel()
            viewModel.uiState.test {
                advanceUntilIdle()
                awaitItem()
                // A user who changed nothing: the built-in defaults, read for real.
                settingsArrive(
                    order = InboxPill.entries.toList(),
                    hidden = emptySet(),
                    unreadToggle = true,
                    defaultFilter = null,
                )
                advanceUntilIdle()
                val loaded = awaitItem()
                assertThat(loaded).isEqualTo(
                    InboxUiState(
                        unreadCounts = mapOf(Category.PERSONAL to 2, Category.OTP to 1),
                        pills = InboxPillConfig(order = InboxPill.entries.toList(), hidden = emptySet()),
                        totalUnread = 3,
                        loaded = true,
                    ),
                )
            }
        }

    // ------------------------------------------------------------------
    // The gate in the screens, source-pinned (no Compose UI harness): a
    // future edit that renders the pill row, the Unread switch or the body
    // outside the loaded gate fails here.
    // ------------------------------------------------------------------

    private fun source(path: String) = File("src/main/kotlin/app/clearsms/$path").readText()

    @Test
    fun `the inbox seeds its state on the default and marks only real emissions loaded`() {
        val vm = source("ui/inbox/InboxViewModel.kt")
        assertThat(vm).contains(".stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), InboxUiState())")
        assertThat(vm).contains("loaded = true,")
        // The default filter is part of the read: no seeded InboxFilterState().
        assertThat(vm).contains("MutableStateFlow<InboxFilterState?>(null)")
        assertThat(vm).contains("filter.compareAndSet(null, InboxFilterState(pill = startCategory?.let(InboxPill::of)))")
        assertThat(vm).contains("combine(filter.filterNotNull(), pillConfig, settings.inboxUnreadToggle)")
    }

    @Test
    fun `inbox pill row and body compose only once loaded`() {
        val inbox = source("ui/inbox/InboxScreen.kt")
        // The body's gate is the LAST loaded check in the file (the title's
        // is the first, pinned below); it heads the if/else-if chain that
        // owns the empty state, the pill row and the list.
        val gate = inbox.lastIndexOf("if (!state.loaded) {")
        val pills = inbox.indexOf("item(key = \"filters\")")
        val list = inbox.indexOf("LazyColumn(state = listState")
        val empty = inbox.indexOf("icon = Icons.Outlined.Inbox,")
        assertThat(gate).isGreaterThan(-1)
        assertThat(inbox.indexOf("} else if (emptyLoaded && state.filter == InboxFilterState()) {", gate)).isGreaterThan(gate)
        assertThat(empty).isGreaterThan(gate)
        assertThat(list).isGreaterThan(gate)
        assertThat(pills).isGreaterThan(gate)
        // Two gates, one per surface: the title's switch and the body.
        assertThat(inbox.indexOf("if (!state.loaded) {")).isLessThan(gate)
    }

    @Test
    fun `inbox unread switch composes only once loaded`() {
        val inbox = source("ui/inbox/InboxScreen.kt")
        val gate = inbox.indexOf("if (!state.loaded) {")
        val toggle = inbox.indexOf("UnreadSwitch(")
        assertThat(gate).isGreaterThan(-1)
        assertThat(toggle).isGreaterThan(gate)
        // The unloaded branch yields no trailing content at all.
        assertThat(inbox.substring(gate, toggle)).contains("null")
        assertThat(inbox.substring(gate, toggle)).contains("} else if (state.showUnreadToggle) {")
    }

    @Test
    fun `finance and alerts pill rows share the hazard and the gate`() {
        listOf("ui/finance/FinanceScreen.kt", "ui/alerts/AlertsScreen.kt").forEach { path ->
            val screen = source(path)
            val gate = screen.indexOf("if (!state.loaded) return@Scaffold")
            val pills = screen.indexOf("if (state.pills.showsRow) {")
            assertThat(gate).isGreaterThan(-1)
            assertThat(pills).isGreaterThan(gate)
        }
        // Both states seed on the built-in pill set - the same flash shape.
        assertThat(
            source("ui/finance/FinanceViewModel.kt"),
        ).contains("val pills: PillConfig<FinanceTab> = PillConfig(FinanceTab.entries.toList()),")
        assertThat(
            source("ui/alerts/AlertsViewModel.kt"),
        ).contains("val pills: PillConfig<AlertFilter> = PillConfig(AlertFilter.entries.toList()),")
    }
}
