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

/** Recycle-bin restore, delete-forever, empty-bin and the 30-day purge. */
@RunWith(RobolectricTestRunner::class)
class RecycleBinTest {
    private lateinit var db: ClearSmsDatabase
    private lateinit var repository: MessageRepositoryImpl
    private val deletedFromProvider = mutableListOf<List<Long>>()
    private val reinsertedInbox = mutableListOf<Triple<String, String, Long>>()
    private val reinsertedSent = mutableListOf<Triple<String, String, Long>>()

    /** Next provider row id handed out by the fake reinserter; null = fail. */
    private var nextProviderId: Long? = 9_000L

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
                systemSmsReinserter =
                    object : SystemSmsReinserter {
                        override fun reinsertInbox(
                            sender: String,
                            body: String,
                            timestampMs: Long,
                            read: Boolean,
                        ): Long? {
                            reinsertedInbox += Triple(sender, body, timestampMs)
                            return nextProviderId?.also { nextProviderId = it + 1 }
                        }

                        override fun reinsertSent(
                            destination: String,
                            body: String,
                            timestampMs: Long,
                        ): Long? {
                            reinsertedSent += Triple(destination, body, timestampMs)
                            return nextProviderId?.also { nextProviderId = it + 1 }
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
        outgoing: Boolean = false,
        timestamp: Long = id,
    ) = MessageEntity(
        id = id,
        threadId = threadId,
        sender = "sender-$threadId",
        normalizedSender = "sender-$threadId",
        body = "body $id",
        timestamp = timestamp,
        isRead = true,
        category = Category.PERSONAL,
        systemSmsId = systemSmsId,
        isOutgoing = outgoing,
    )

    /** Stages + commits [ids] into the bin, clearing provider bookkeeping noise. */
    private suspend fun binMessages(vararg ids: Long) {
        repository.commitStagedDelete(repository.stageDeleteMessages(ids.toList()), toBin = true)
        deletedFromProvider.clear()
    }

    @Test
    fun `restore brings the message back to the inbox and re-inserts it into the provider`() =
        runBlocking<Unit> {
            db.messageDao().insertAll(listOf(message(1, systemSmsId = 101)))
            binMessages(1L)

            val result = repository.restoreFromBin(listOf(1L))

            assertThat(result.restored).isEqualTo(1)
            assertThat(result.fullyReinserted).isTrue()
            assertThat(reinsertedInbox).containsExactly(Triple("sender-1", "body 1", 1L))
            assertThat(repository.observeInbox(null, false).first().map { it.id }).containsExactly(1L)
            assertThat(repository.observeBin().first()).isEmpty()
            // The row maps to its FRESH provider row, not the deleted one.
            assertThat(db.messageDao().getById(1)?.systemSmsId).isEqualTo(9_000L)
        }

    @Test
    fun `restore of an outgoing message goes through the sent box`() =
        runBlocking<Unit> {
            db.messageDao().insertAll(listOf(message(1, outgoing = true)))
            binMessages(1L)

            repository.restoreFromBin(listOf(1L))

            assertThat(reinsertedSent).hasSize(1)
            assertThat(reinsertedInbox).isEmpty()
        }

    @Test
    fun `failed provider re-insert still restores in-app and reports it`() =
        runBlocking<Unit> {
            db.messageDao().insertAll(listOf(message(1, systemSmsId = 101)))
            binMessages(1L)
            nextProviderId = null // not the default SMS app / insert failed

            val result = repository.restoreFromBin(listOf(1L))

            assertThat(result.restored).isEqualTo(1)
            assertThat(result.fullyReinserted).isFalse()
            assertThat(repository.observeInbox(null, false).first().map { it.id }).containsExactly(1L)
            // The stale provider id must not survive: that row is gone.
            assertThat(db.messageDao().getById(1)?.systemSmsId).isNull()
        }

    @Test
    fun `delete forever purges the row exactly like a hard delete`() =
        runBlocking<Unit> {
            db.messageDao().insertAll(listOf(message(1), message(2)))
            binMessages(1L)

            repository.deleteForever(listOf(1L))

            assertThat(db.messageDao().getAll().map { it.id }).containsExactly(2L)
            assertThat(repository.observeBin().first()).isEmpty()
        }

    @Test
    fun `empty bin removes every binned message and nothing else`() =
        runBlocking<Unit> {
            db.messageDao().insertAll(listOf(message(1), message(2, threadId = 2), message(3, threadId = 3)))
            binMessages(1L, 2L)

            repository.deleteForever(repository.binMessageIds())

            assertThat(db.messageDao().getAll().map { it.id }).containsExactly(3L)
        }

    @Test
    fun `30-day sweep purges only expired bin rows`() =
        runBlocking<Unit> {
            db.messageDao().insertAll(listOf(message(1), message(2, threadId = 2), message(3, threadId = 3)))
            // Bin rows deleted at two different times.
            db.messageDao().stageDelete(listOf(1L), deletedAt = 1_000L)
            db.messageDao().stageDelete(listOf(2L), deletedAt = 50_000L)
            db.messageDao().clearProviderPending(listOf(1L, 2L))

            val purged = repository.purgeExpiredBin(cutoffMs = 10_000L)

            assertThat(purged).isEqualTo(1)
            // Only the expired row went; the fresher bin row and the live
            // message survive.
            assertThat(db.messageDao().getAll().map { it.id }).containsExactly(2L, 3L)
            assertThat(repository.observeBin().first().map { it.id }).containsExactly(2L)
        }

    private object NoopDataStore : DataStore<Preferences> {
        override val data: Flow<Preferences> = flowOf(emptyPreferences())

        override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences): Preferences = emptyPreferences()
    }
}
