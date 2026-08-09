package app.clearsms.data.repository

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import app.clearsms.data.db.ClearSmsDatabase
import app.clearsms.data.db.MessageEntity
import app.clearsms.data.rules.BundledRuleLoader
import app.clearsms.data.rules.RuleEngine
import app.clearsms.domain.categorizer.ContactLookup
import app.clearsms.domain.categorizer.MessageCategorizer
import app.clearsms.domain.categorizer.SenderIdLookup
import app.clearsms.domain.model.Category
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The deferred-commit delete design: staging soft-deletes the app rows
 * immediately (hidden everywhere, notifications cancelled) while the
 * system-provider deletion waits for the commit — so undo is a pure flag
 * revert and the provider is never touched inside the window.
 */
@RunWith(RobolectricTestRunner::class)
class UndoDeleteTest {
    private lateinit var db: ClearSmsDatabase
    private lateinit var repository: MessageRepositoryImpl
    private val deletedFromProvider = mutableListOf<List<Long>>()
    private val cancelledMessageIds = mutableListOf<List<Long>>()
    private val cancelledThreadIds = mutableListOf<List<Long>>()

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db =
            Room
                .inMemoryDatabaseBuilder(context, ClearSmsDatabase::class.java)
                .allowMainThreadQueries()
                .build()
        val json = Json { ignoreUnknownKeys = true }
        repository =
            MessageRepositoryImpl(
                database = db,
                categorizer =
                    MessageCategorizer(
                        ruleEngine = RuleEngine(),
                        senderIdLookup = SenderIdLookup { null },
                        contactLookup = ContactLookup { false },
                    ),
                bundledRuleLoader = BundledRuleLoader(context, db.ruleDao(), json, NoopDataStore),
                json = json,
                systemSmsDeleter = { ids ->
                    deletedFromProvider += ids
                    ids.size
                },
                readNotificationCanceler =
                    object : ReadNotificationCanceler {
                        override fun cancelFor(messageIds: List<Long>) {
                            cancelledMessageIds += messageIds
                        }

                        override fun cancelThreads(threadIds: List<Long>) {
                            cancelledThreadIds += threadIds
                        }
                    },
            )
    }

    @After
    fun tearDown() {
        db.close()
    }

    private fun message(
        id: Long,
        threadId: Long = 1L,
        systemSmsId: Long? = null,
        category: Category = Category.PERSONAL,
        timestamp: Long = id,
    ) = MessageEntity(
        id = id,
        threadId = threadId,
        sender = "sender-$threadId",
        normalizedSender = "sender-$threadId",
        body = "hello body $id",
        timestamp = timestamp,
        isRead = true,
        category = category,
        systemSmsId = systemSmsId,
    )

    @Test
    fun `staging hides the message from inbox and search but never touches the provider`() =
        runBlocking<Unit> {
            db.messageDao().insertAll(listOf(message(1, systemSmsId = 101), message(2, threadId = 2)))

            val staged = repository.stageDeleteMessages(listOf(1L))

            assertThat(staged).containsExactly(1L)
            assertThat(repository.observeInbox(null, false).first().map { it.id }).containsExactly(2L)
            assertThat(repository.search("hello").first().map { it.id }).containsExactly(2L)
            // The deferred design's whole point: nothing forwarded yet.
            assertThat(deletedFromProvider).isEmpty()
            // The row still exists (soft-deleted), keeping undo trivial.
            assertThat(db.messageDao().getById(1)?.deletedAt).isNotNull()
        }

    @Test
    fun `undo restores the app row and the provider was never called`() =
        runBlocking<Unit> {
            db.messageDao().insertAll(listOf(message(1, systemSmsId = 101)))
            val staged = repository.stageDeleteMessages(listOf(1L))

            repository.undoStagedDelete(staged)

            assertThat(repository.observeInbox(null, false).first().map { it.id }).containsExactly(1L)
            assertThat(deletedFromProvider).isEmpty()
            val row = db.messageDao().getById(1)!!
            assertThat(row.deletedAt).isNull()
            assertThat(row.providerDeletePending).isFalse()
            // The provider mapping survives untouched: no re-insertion needed.
            assertThat(row.systemSmsId).isEqualTo(101L)
        }

    @Test
    fun `commit with bin OFF hard-deletes the rows and the provider copies`() =
        runBlocking<Unit> {
            db.messageDao().insertAll(listOf(message(1, systemSmsId = 101), message(2, systemSmsId = 102)))
            val staged = repository.stageDeleteMessages(listOf(1L, 2L))

            repository.commitStagedDelete(staged, toBin = false)

            assertThat(db.messageDao().getAll()).isEmpty()
            assertThat(deletedFromProvider.flatten()).containsExactly(101L, 102L)
        }

    @Test
    fun `commit with bin ON keeps the rows in the bin while the provider copies are deleted`() =
        runBlocking<Unit> {
            db.messageDao().insertAll(listOf(message(1, systemSmsId = 101)))
            val staged = repository.stageDeleteMessages(listOf(1L))

            repository.commitStagedDelete(staged, toBin = true)

            // Other SMS apps forget the message; ours retains the bin copy.
            assertThat(deletedFromProvider.flatten()).containsExactly(101L)
            assertThat(repository.observeBin().first().map { it.id }).containsExactly(1L)
            // Excluded from inbox and search everywhere.
            assertThat(repository.observeInbox(null, false).first()).isEmpty()
            assertThat(repository.search("hello").first()).isEmpty()
            assertThat(db.messageDao().getById(1)?.providerDeletePending).isFalse()
        }

    @Test
    fun `pending commit survives restart - commitAllPendingDeletes finishes the provider deletion`() =
        runBlocking<Unit> {
            db.messageDao().insertAll(listOf(message(1, systemSmsId = 101)))
            repository.stageDeleteMessages(listOf(1L))
            // Process death before the window closed: nothing committed yet.
            assertThat(deletedFromProvider).isEmpty()

            // Next launch (bin OFF): the message must be gone everywhere.
            repository.commitAllPendingDeletes(toBin = false)

            assertThat(deletedFromProvider.flatten()).containsExactly(101L)
            assertThat(db.messageDao().getAll()).isEmpty()
        }

    @Test
    fun `staging a whole thread stages every message of the thread`() =
        runBlocking<Unit> {
            db.messageDao().insertAll(
                listOf(
                    message(1, threadId = 1, systemSmsId = 11),
                    message(2, threadId = 1, systemSmsId = 12),
                    message(3, threadId = 2, systemSmsId = 21),
                ),
            )

            val staged = repository.stageDeleteThreads(listOf(1L))

            assertThat(staged).containsExactly(1L, 2L)
            assertThat(repository.observeInbox(null, false).first().map { it.threadId }).containsExactly(2L)
            assertThat(deletedFromProvider).isEmpty()
        }

    @Test
    fun `staging cancels notifications once and undo does not resurrect them`() =
        runBlocking<Unit> {
            db.messageDao().insertAll(listOf(message(1)))

            val staged = repository.stageDeleteMessages(listOf(1L))
            assertThat(cancelledMessageIds.flatten()).containsExactly(1L)

            cancelledMessageIds.clear()
            repository.undoStagedDelete(staged)
            // Undo is a silent flag revert: no notification is re-posted and
            // no further cancellation happens either.
            assertThat(cancelledMessageIds).isEmpty()
        }

    @Test
    fun `OTP auto-delete path has no undo - it commits immediately`() =
        runBlocking<Unit> {
            db.messageDao().insertAll(
                listOf(
                    message(1, category = Category.OTP, systemSmsId = 101, timestamp = 1_000),
                    message(2, category = Category.OTP, systemSmsId = 102, timestamp = 9_000),
                ),
            )

            val deleted = repository.deleteOtpOlderThan(cutoffMs = 5_000)

            assertThat(deleted).isEqualTo(1)
            // Row hard-deleted AND provider synced in one step — nothing is
            // left pending for an undo window to revert.
            assertThat(db.messageDao().getAll().map { it.id }).containsExactly(2L)
            assertThat(deletedFromProvider.flatten()).containsExactly(101L)
            assertThat(db.messageDao().pendingCommitIds()).isEmpty()
        }

    @Test
    fun `OTP cleanup ignores messages resting in the bin`() =
        runBlocking<Unit> {
            db.messageDao().insertAll(
                listOf(
                    message(1, category = Category.OTP, timestamp = 1_000),
                    message(2, category = Category.OTP, timestamp = 2_000),
                ),
            )
            repository.commitStagedDelete(repository.stageDeleteMessages(listOf(1L)), toBin = true)

            val deleted = repository.deleteOtpOlderThan(cutoffMs = 5_000)

            // Only the live OTP goes; the binned one rests untouched.
            assertThat(deleted).isEqualTo(1)
            assertThat(repository.observeBin().first().map { it.id }).containsExactly(1L)
        }

    private object NoopDataStore : DataStore<Preferences> {
        override val data: Flow<Preferences> = flowOf(emptyPreferences())

        override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences): Preferences = emptyPreferences()
    }
}
