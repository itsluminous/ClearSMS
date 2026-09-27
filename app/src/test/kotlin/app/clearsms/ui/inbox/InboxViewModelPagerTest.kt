package app.clearsms.ui.inbox

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.work.WorkManager
import androidx.work.testing.WorkManagerTestInitHelper
import app.cash.turbine.test
import app.clearsms.data.db.CategoryUnreadCount
import app.clearsms.data.db.ClearSmsDatabase
import app.clearsms.data.db.MessageEntity
import app.clearsms.data.prefs.SettingsRepository
import app.clearsms.data.repository.SenderBlocker
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
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Regression coverage for the inbox jumping to the top: the pager behind
 * `flatMapLatest` must be rebuilt ONLY when the query changes. Each pager
 * generation calls `pagedInbox` exactly once, so `pagedInboxCalls` counts
 * generations; [InboxViewModel.pagerKeys] is the rebuild trigger itself.
 *
 * Two generations with ONE key emission is an in-place refresh (Paging
 * reloading around its anchor after a `PagingSource.invalidate()`, which
 * is what Room does on every write); two key emissions is a rebuild.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class InboxViewModelPagerTest {
    private val dispatcher = StandardTestDispatcher()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    private lateinit var context: Context
    private lateinit var db: ClearSmsDatabase
    private lateinit var repository: FakeMessageRepository
    private lateinit var settings: FakeSettingsRepository

    /** Stands in for DataStore: replays, and re-emits an UNCHANGED value on any write. */
    private val sortOrders = MutableSharedFlow<MessageSortOrder>(replay = 1).apply { tryEmit(MessageSortOrder.RECEIVED) }

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        context = ApplicationProvider.getApplicationContext()
        WorkManagerTestInitHelper.initializeTestWorkManager(context)
        db = Room.inMemoryDatabaseBuilder(context, ClearSmsDatabase::class.java).allowMainThreadQueries().build()
        repository = FakeMessageRepository()
        repository.inbox.value = listOf(message(id = 1, read = false), message(id = 2, read = true))
        repository.unreadCounts.value = listOf(CategoryUnreadCount(Category.PERSONAL, 1))
        settings = FakeSettingsRepository()
    }

    @After
    fun tearDown() {
        // No worker may be left RUNNING once the Work database closes.
        WorkManager
            .getInstance(context)
            .cancelAllWork()
            .result
            .get()
        scope.cancel()
        db.close()
        Dispatchers.resetMain()
    }

    private fun message(
        id: Long,
        read: Boolean,
    ) = MessageEntity(
        id = id,
        threadId = id,
        sender = "98765${id}0000",
        normalizedSender = "98765${id}0000",
        body = "synthetic body $id",
        timestamp = 1_000 * id,
        category = Category.PERSONAL,
        isRead = read,
    )

    private fun viewModel(): InboxViewModel {
        // Only the sort order is swapped for the DataStore stand-in.
        val dataStoreLike =
            object : SettingsRepository by settings {
                override val messageSortOrder: Flow<MessageSortOrder> = sortOrders
            }
        return InboxViewModel(
            messageRepository = repository,
            undoManager = UndoManager(repository, scope, { true }),
            senderBlocker = SenderBlocker(settings, repository, InMemoryPreferencesDataStore(), scope),
            senderIdLookup = SenderIdLookup { null },
            contactsSource = ContactsSource(context),
            settings = dataStoreLike,
            catchUpSyncScheduler = CatchUpSyncScheduler(context, db.messageDao(), WorkManager.getInstance(context), dispatcher),
            workManager = WorkManager.getInstance(context),
            ioDispatcher = dispatcher,
        )
    }

    private fun TestScope.collectPager(viewModel: InboxViewModel): Job = launch { viewModel.pagedItems.collect {} }

    /** Everything marking a thread read does that the inbox can observe. */
    private fun simulateThreadMarkedRead() {
        repository.inbox.value = repository.inbox.value.map { it.copy(isRead = true) }
        repository.unreadCounts.value = emptyList()
        repository.lastInboxSource!!.invalidate()
    }

    @Test
    fun `marking a thread read refreshes in place - the pager is not rebuilt`() =
        runTest(dispatcher) {
            val viewModel = viewModel()
            val job = collectPager(viewModel)
            viewModel.pagerKeys.test {
                advanceUntilIdle()
                assertThat(awaitItem()).isEqualTo(InboxPagerKey(null, false, MessageSortOrder.RECEIVED))
                assertThat(repository.pagedInboxCalls).hasSize(1)

                simulateThreadMarkedRead()
                advanceUntilIdle()

                // Room's invalidation produced the second generation; no key
                // emission means flatMapLatest never restarted the pager.
                expectNoEvents()
                assertThat(repository.pagedInboxCalls).hasSize(2)
            }
            job.cancel()
        }

    @Test
    fun `a settings write that leaves the sort order unchanged does not rebuild the pager`() =
        runTest(dispatcher) {
            val viewModel = viewModel()
            val job = collectPager(viewModel)
            viewModel.pagerKeys.test {
                advanceUntilIdle()
                awaitItem()
                assertThat(repository.pagedInboxCalls).hasSize(1)

                // DataStore re-emits the whole preferences snapshot on ANY
                // write - e.g. dismissing the OTP banner or blocking a sender.
                sortOrders.emit(MessageSortOrder.RECEIVED)
                sortOrders.emit(MessageSortOrder.RECEIVED)
                advanceUntilIdle()

                expectNoEvents()
                assertThat(repository.pagedInboxCalls).hasSize(1)
            }
            job.cancel()
        }

    @Test
    fun `contacts becoming available refreshes rows in place`() =
        runTest(dispatcher) {
            val viewModel = viewModel()
            val job = collectPager(viewModel)
            viewModel.pagerKeys.test {
                advanceUntilIdle()
                awaitItem()

                viewModel.onContactsPermissionGranted()
                advanceUntilIdle()

                expectNoEvents()
                assertThat(repository.pagedInboxCalls).hasSize(2)
            }
            job.cancel()
        }

    @Test
    fun `a real query change rebuilds the pager exactly once`() =
        runTest(dispatcher) {
            val viewModel = viewModel()
            val job = collectPager(viewModel)
            viewModel.pagerKeys.test {
                advanceUntilIdle()
                awaitItem()

                viewModel.selectPill(InboxPill.of(Category.IMPORTANT))
                advanceUntilIdle()
                assertThat(awaitItem()).isEqualTo(InboxPagerKey(Category.IMPORTANT, false, MessageSortOrder.RECEIVED))
                assertThat(repository.pagedInboxCalls).hasSize(2)
                assertThat(repository.pagedInboxCalls.last()).isEqualTo(Triple(Category.IMPORTANT, false, MessageSortOrder.RECEIVED))

                sortOrders.emit(MessageSortOrder.SENT)
                advanceUntilIdle()
                assertThat(awaitItem()).isEqualTo(InboxPagerKey(Category.IMPORTANT, false, MessageSortOrder.SENT))
                assertThat(repository.pagedInboxCalls).hasSize(3)
            }
            job.cancel()
        }
}
