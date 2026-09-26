package app.clearsms.work

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import app.clearsms.data.db.ClearSmsDatabase
import app.clearsms.data.db.DateSentUpdate
import app.clearsms.data.db.MessageEntity
import app.clearsms.domain.model.Category
import app.clearsms.sms.ProviderSentTimeSource
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Pins the one-time sent-time backfill's safety contract, mirroring
 * [SimBackfillTest]: fills ONLY incoming rows lacking a sent time, verifies
 * body+received-time identity before every write (provider ids are reused -
 * a bare `systemSmsId` match must never attach another message's sent
 * time), never guesses from a 0/absent `date_sent`, is idempotent,
 * resumable, batched, and runs once per version. Fixtures are synthetic.
 */
@RunWith(RobolectricTestRunner::class)
class SentTimeBackfillTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val openDbs = mutableListOf<ClearSmsDatabase>()

    private class FakeSource : ProviderSentTimeSource {
        val rows = mutableListOf<ProviderSentTimeSource.ProviderSentTime>()
        var pagesServed = 0

        override fun page(
            afterId: Long,
            limit: Int,
        ): List<ProviderSentTimeSource.ProviderSentTime> {
            pagesServed++
            return rows.filter { it.id > afterId }.sortedBy { it.id }.take(limit)
        }
    }

    private inner class Env(
        name: String,
    ) {
        val db: ClearSmsDatabase =
            Room
                .inMemoryDatabaseBuilder(context, ClearSmsDatabase::class.java)
                .allowMainThreadQueries()
                .build()
                .also { openDbs += it }
        val dataStore: DataStore<Preferences> =
            PreferenceDataStoreFactory.create(scope = scope) {
                tmp.newFile("$name.preferences_pb")
            }
        val source = FakeSource()
        val backfill = SentTimeBackfill(dataStore, db.messageDao(), source, Dispatchers.IO)

        suspend fun insert(
            systemSmsId: Long?,
            body: String,
            timestamp: Long,
            dateSent: Long? = null,
            outgoing: Boolean = false,
        ): Long =
            db.messageDao().insert(
                MessageEntity(
                    threadId = 1L,
                    sender = "9876543210",
                    normalizedSender = "9876543210",
                    body = body,
                    timestamp = timestamp,
                    category = Category.PERSONAL,
                    systemSmsId = systemSmsId,
                    dateSent = dateSent,
                    isOutgoing = outgoing,
                ),
            )
    }

    @After
    fun tearDown() {
        openDbs.forEach { it.close() }
        scope.cancel()
    }

    private fun provider(
        id: Long,
        body: String?,
        dateMs: Long,
        dateSentMs: Long?,
    ) = ProviderSentTimeSource.ProviderSentTime(id, body, dateMs, dateSentMs)

    @Test
    fun `fills only incoming rows that lack a sent time and leaves recorded values alone`() =
        runBlocking {
            val env = Env("fill")
            val lacking = env.insert(systemSmsId = 1L, body = "hello", timestamp = 1_000L)
            val recorded = env.insert(systemSmsId = 2L, body = "world", timestamp = 2_000L, dateSent = 1_900L)
            env.source.rows += provider(1, "hello", 1_000L, dateSentMs = 700L)
            // The provider claims a DIFFERENT sent time for the already-
            // recorded row; the live-recorded value must win.
            env.source.rows += provider(2, "world", 2_000L, dateSentMs = 1_500L)

            val filled = env.backfill.runIfNeeded()

            assertThat(filled).isEqualTo(1)
            val all =
                env.db
                    .messageDao()
                    .getAll()
                    .associateBy { it.id }
            assertThat(all.getValue(lacking).dateSent).isEqualTo(700L)
            assertThat(all.getValue(recorded).dateSent).isEqualTo(1_900L)
        }

    @Test
    fun `refuses a reused provider id whose body or timestamp differ`() =
        runBlocking {
            // THE critical case (v0.14.1 lesson): the provider's _id is a
            // plain INTEGER PRIMARY KEY, reused after deletions. The stored
            // row's provider copy was deleted; id 5 now belongs to a NEW
            // message. Matching on systemSmsId alone would attach the new
            // message's sent time to the old row - identity must refuse it.
            val env = Env("reuse")
            val stale = env.insert(systemSmsId = 5L, body = "old deleted message", timestamp = 1_000L)
            env.source.rows += provider(5, "brand new message", 2_000L, dateSentMs = 1_950L)

            val filled = env.backfill.runIfNeeded()

            assertThat(filled).isEqualTo(0)
            assertThat(
                env.db
                    .messageDao()
                    .getAll()
                    .single { it.id == stale }
                    .dateSent,
            ).isNull()
        }

    @Test
    fun `a null provider body or an unreported sent time leaves the row unknown`() =
        runBlocking {
            val env = Env("unknown")
            env.insert(systemSmsId = 1L, body = "no body upstream", timestamp = 1_000L)
            env.insert(systemSmsId = 2L, body = "network stamped nothing", timestamp = 2_000L)
            // Identity cannot be verified without a body → skip; a null
            // (0/absent) date_sent is unknown → skip. Never a guess.
            env.source.rows += provider(1, null, 1_000L, dateSentMs = 900L)
            env.source.rows += provider(2, "network stamped nothing", 2_000L, dateSentMs = null)

            assertThat(env.backfill.runIfNeeded()).isEqualTo(0)
            assertThat(
                env.db
                    .messageDao()
                    .getAll()
                    .all { it.dateSent == null },
            ).isTrue()
        }

    @Test
    fun `outgoing rows are never touched - their send time is their own timestamp`() =
        runBlocking {
            val env = Env("outgoing")
            val sent = env.insert(systemSmsId = 1L, body = "my reply", timestamp = 1_000L, outgoing = true)
            env.source.rows += provider(1, "my reply", 1_000L, dateSentMs = 900L)

            assertThat(env.backfill.runIfNeeded()).isEqualTo(0)
            assertThat(
                env.db
                    .messageDao()
                    .getAll()
                    .single { it.id == sent }
                    .dateSent,
            ).isNull()
        }

    @Test
    fun `is idempotent and runs only once per version`() =
        runBlocking {
            val env = Env("once")
            env.insert(systemSmsId = 1L, body = "hello", timestamp = 1_000L)
            env.source.rows += provider(1, "hello", 1_000L, dateSentMs = 800L)

            assertThat(env.backfill.runIfNeeded()).isEqualTo(1)

            // A second call is a no-op: the version marker short-circuits
            // before any provider read, even when new matching rows exist.
            env.insert(systemSmsId = 2L, body = "later", timestamp = 2_000L)
            env.source.rows += provider(2, "later", 2_000L, dateSentMs = 1_800L)
            val pagesBefore = env.source.pagesServed

            assertThat(env.backfill.runIfNeeded()).isEqualTo(0)
            assertThat(env.source.pagesServed).isEqualTo(pagesBefore)
            val all = env.db.messageDao().getAll()
            assertThat(all.single { it.systemSmsId == 1L }.dateSent).isEqualTo(800L)
            assertThat(all.single { it.systemSmsId == 2L }.dateSent).isNull()
        }

    @Test
    fun `a fresh install never touches the provider - empty database marks the version done`() =
        runBlocking {
            val env = Env("fresh")
            // Provider has history, but nothing was imported yet: the
            // importer will record date_sent itself. The backfill must
            // decide from the DB alone - zero provider pages read - and mark
            // the version done so it never runs again.
            env.source.rows += provider(1, "hello", 1_000L, dateSentMs = 900L)

            assertThat(env.backfill.runIfNeeded()).isEqualTo(0)
            assertThat(env.source.pagesServed).isEqualTo(0)
            val prefs = env.dataStore.data.first()
            assertThat(prefs[SentTimeBackfill.KEY_DONE_VERSION]).isEqualTo(SentTimeBackfill.VERSION)
        }

    @Test
    fun `does not re-walk rows the importer already filled`() =
        runBlocking {
            val env = Env("importerFilled")
            // Every imported incoming row already carries its sent time
            // (new importer); outgoing rows never need one. Nothing a
            // provider walk could fill, so none happens.
            env.insert(systemSmsId = 1L, body = "hello", timestamp = 1_000L, dateSent = 900L)
            env.insert(systemSmsId = 2L, body = "my reply", timestamp = 2_000L, outgoing = true)
            env.source.rows += provider(1, "hello", 1_000L, dateSentMs = 900L)

            assertThat(env.backfill.runIfNeeded()).isEqualTo(0)
            assertThat(env.source.pagesServed).isEqualTo(0)
            assertThat(
                env.dataStore.data
                    .first()[SentTimeBackfill.KEY_DONE_VERSION],
            ).isEqualTo(SentTimeBackfill.VERSION)
        }

    @Test
    fun `an interrupted run still finishes its pass even if remaining rows look filled`() =
        runBlocking {
            val env = Env("resumeNotSkipped")
            // A checkpoint means an earlier run proved work existed: the
            // nothing-to-do shortcut must not strand the pass short of its
            // completion marker.
            env.insert(systemSmsId = 2L, body = "after checkpoint", timestamp = 2_000L)
            env.source.rows += provider(2, "after checkpoint", 2_000L, dateSentMs = 1_900L)
            env.dataStore.edit { it[SentTimeBackfill.KEY_LAST_PROVIDER_ID] = 1L }

            assertThat(env.backfill.runIfNeeded()).isEqualTo(1)
            assertThat(env.source.pagesServed).isAtLeast(1)
            val prefs = env.dataStore.data.first()
            assertThat(prefs[SentTimeBackfill.KEY_DONE_VERSION]).isEqualTo(SentTimeBackfill.VERSION)
            assertThat(prefs[SentTimeBackfill.KEY_LAST_PROVIDER_ID]).isNull()
        }

    @Test
    fun `writes are batched - one grouped DAO update per page, not one per row`() =
        runBlocking {
            val env = Env("batch")
            // 40 rows lacking a sent time, all in one provider page: the
            // write cost must be ONE grouped update, never 40 single-row
            // transactions (each row has its own value, so the batch is a
            // partial-entity update list, not a grouped IN () by value).
            for (i in 1L..40L) {
                env.insert(systemSmsId = i, body = "msg $i", timestamp = i * 1_000L)
                env.source.rows += provider(i, "msg $i", i * 1_000L, dateSentMs = i * 1_000L - 250L)
            }
            val counting = CountingDao(env.db.messageDao())
            val backfill = SentTimeBackfill(env.dataStore, counting, env.source, Dispatchers.IO)

            assertThat(backfill.runIfNeeded()).isEqualTo(40)

            assertThat(counting.groupedWrites).isEqualTo(1)
            assertThat(counting.rowsWritten).isEqualTo(40)
            assertThat(
                env.db
                    .messageDao()
                    .getAll()
                    .all { it.dateSent == it.timestamp - 250L },
            ).isTrue()
        }

    @Test
    fun `grouped writes still refuse reused provider ids - the guard gates the batch`() =
        runBlocking {
            // The batching optimization must not weaken identity: a reused
            // id in the middle of an otherwise-verified page stays null
            // while its verified neighbours are filled.
            val env = Env("batchGuard")
            env.insert(systemSmsId = 1L, body = "genuine one", timestamp = 1_000L)
            val stale = env.insert(systemSmsId = 2L, body = "old deleted message", timestamp = 2_000L)
            env.insert(systemSmsId = 3L, body = "genuine three", timestamp = 3_000L)
            env.source.rows += provider(1, "genuine one", 1_000L, dateSentMs = 900L)
            env.source.rows += provider(2, "brand new message", 9_000L, dateSentMs = 8_900L)
            env.source.rows += provider(3, "genuine three", 3_000L, dateSentMs = 2_900L)

            assertThat(env.backfill.runIfNeeded()).isEqualTo(2)

            val all = env.db.messageDao().getAll()
            assertThat(all.single { it.id == stale }.dateSent).isNull()
            assertThat(all.single { it.systemSmsId == 1L }.dateSent).isEqualTo(900L)
            assertThat(all.single { it.systemSmsId == 3L }.dateSent).isEqualTo(2_900L)
        }

    /** Counts sent-time writes; everything else delegates to the real DAO. */
    private class CountingDao(
        private val delegate: app.clearsms.data.db.MessageDao,
    ) : app.clearsms.data.db.MessageDao by delegate {
        var groupedWrites = 0
        var rowsWritten = 0

        override suspend fun setDateSentBatch(updates: List<DateSentUpdate>) {
            groupedWrites++
            rowsWritten += updates.size
            delegate.setDateSentBatch(updates)
        }
    }

    @Test
    fun `resumes from the durable page checkpoint after an interruption`() =
        runBlocking {
            val env = Env("resume")
            env.insert(systemSmsId = 1L, body = "before checkpoint", timestamp = 1_000L)
            env.insert(systemSmsId = 2L, body = "after checkpoint", timestamp = 2_000L)
            env.source.rows += provider(1, "before checkpoint", 1_000L, dateSentMs = 900L)
            env.source.rows += provider(2, "after checkpoint", 2_000L, dateSentMs = 1_900L)
            // Simulate an interrupted earlier run that had committed through
            // provider id 1: the resumed pass must not rescan it.
            env.dataStore.edit { it[SentTimeBackfill.KEY_LAST_PROVIDER_ID] = 1L }

            assertThat(env.backfill.runIfNeeded()).isEqualTo(1)

            val all = env.db.messageDao().getAll()
            assertThat(all.single { it.systemSmsId == 1L }.dateSent).isNull()
            assertThat(all.single { it.systemSmsId == 2L }.dateSent).isEqualTo(1_900L)
            // Completion sets the version marker and clears the checkpoint.
            val prefs = env.dataStore.data.first()
            assertThat(prefs[SentTimeBackfill.KEY_DONE_VERSION]).isEqualTo(SentTimeBackfill.VERSION)
            assertThat(prefs[SentTimeBackfill.KEY_LAST_PROVIDER_ID]).isNull()
        }
}
