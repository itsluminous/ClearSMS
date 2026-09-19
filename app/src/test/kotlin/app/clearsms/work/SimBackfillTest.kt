package app.clearsms.work

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import app.clearsms.data.db.ClearSmsDatabase
import app.clearsms.data.db.MessageEntity
import app.clearsms.domain.model.Category
import app.clearsms.sms.ProviderSimSource
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
 * Pins the one-time SIM backfill's safety contract: fills ONLY rows lacking
 * a SIM, verifies body+timestamp identity before every write (provider ids
 * are reused - a bare `systemSmsId` match must never attach another
 * message's SIM), is idempotent, resumable, and runs once per version.
 */
@RunWith(RobolectricTestRunner::class)
class SimBackfillTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val openDbs = mutableListOf<ClearSmsDatabase>()

    private class FakeSource : ProviderSimSource {
        val rows = mutableListOf<ProviderSimSource.ProviderSim>()
        var pagesServed = 0

        override fun page(
            afterId: Long,
            limit: Int,
        ): List<ProviderSimSource.ProviderSim> {
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
        val backfill = SimBackfill(dataStore, db.messageDao(), source, Dispatchers.IO)

        suspend fun insert(
            systemSmsId: Long?,
            body: String,
            timestamp: Long,
            subscriptionId: Int? = null,
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
                    subscriptionId = subscriptionId,
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
        subId: Int?,
    ) = ProviderSimSource.ProviderSim(id, body, dateMs, subId)

    @Test
    fun `fills only rows that lack a SIM and leaves recorded values alone`() =
        runBlocking {
            val env = Env("fill")
            val lacking = env.insert(systemSmsId = 1L, body = "hello", timestamp = 1_000L)
            val recorded = env.insert(systemSmsId = 2L, body = "world", timestamp = 2_000L, subscriptionId = 7)
            env.source.rows += provider(1, "hello", 1_000L, subId = 1)
            // The provider claims a DIFFERENT SIM for the already-recorded
            // row; the live-recorded value must win.
            env.source.rows += provider(2, "world", 2_000L, subId = 2)

            val filled = env.backfill.runIfNeeded()

            assertThat(filled).isEqualTo(1)
            val all =
                env.db
                    .messageDao()
                    .getAll()
                    .associateBy { it.id }
            assertThat(all.getValue(lacking).subscriptionId).isEqualTo(1)
            assertThat(all.getValue(recorded).subscriptionId).isEqualTo(7)
        }

    @Test
    fun `refuses a reused provider id whose body or timestamp differ`() =
        runBlocking {
            // THE critical case (v0.14.1 lesson): the provider's _id is a
            // plain INTEGER PRIMARY KEY, reused after deletions. The stored
            // row's provider copy was deleted; id 5 now belongs to a NEW
            // message. Matching on systemSmsId alone would attach the new
            // message's SIM to the old row - identity must refuse it.
            val env = Env("reuse")
            val stale = env.insert(systemSmsId = 5L, body = "old deleted message", timestamp = 1_000L)
            env.source.rows += provider(5, "brand new message", 2_000L, subId = 2)

            val filled = env.backfill.runIfNeeded()

            assertThat(filled).isEqualTo(0)
            assertThat(
                env.db
                    .messageDao()
                    .getAll()
                    .single { it.id == stale }
                    .subscriptionId,
            ).isNull()
        }

    @Test
    fun `a null provider body or invalid subscription leaves the row unknown`() =
        runBlocking {
            val env = Env("unknown")
            env.insert(systemSmsId = 1L, body = "no body upstream", timestamp = 1_000L)
            env.insert(systemSmsId = 2L, body = "invalid sub", timestamp = 2_000L)
            // Identity cannot be verified without a body → skip; -1 is not a
            // SIM → skip. Unknown stays null, never a guess.
            env.source.rows += provider(1, null, 1_000L, subId = 3)
            env.source.rows += provider(2, "invalid sub", 2_000L, subId = null)

            assertThat(env.backfill.runIfNeeded()).isEqualTo(0)
            assertThat(
                env.db
                    .messageDao()
                    .getAll()
                    .all { it.subscriptionId == null },
            ).isTrue()
        }

    @Test
    fun `is idempotent and runs only once per version`() =
        runBlocking {
            val env = Env("once")
            env.insert(systemSmsId = 1L, body = "hello", timestamp = 1_000L)
            env.source.rows += provider(1, "hello", 1_000L, subId = 1)

            assertThat(env.backfill.runIfNeeded()).isEqualTo(1)

            // A second call is a no-op: the version marker short-circuits
            // before any provider read, even when new matching rows exist.
            env.insert(systemSmsId = 2L, body = "later", timestamp = 2_000L)
            env.source.rows += provider(2, "later", 2_000L, subId = 2)
            val pagesBefore = env.source.pagesServed

            assertThat(env.backfill.runIfNeeded()).isEqualTo(0)
            assertThat(env.source.pagesServed).isEqualTo(pagesBefore)
            val all = env.db.messageDao().getAll()
            assertThat(all.single { it.systemSmsId == 1L }.subscriptionId).isEqualTo(1)
            assertThat(all.single { it.systemSmsId == 2L }.subscriptionId).isNull()
        }

    @Test
    fun `resumes from the durable page checkpoint after an interruption`() =
        runBlocking {
            val env = Env("resume")
            env.insert(systemSmsId = 1L, body = "before checkpoint", timestamp = 1_000L)
            env.insert(systemSmsId = 2L, body = "after checkpoint", timestamp = 2_000L)
            env.source.rows += provider(1, "before checkpoint", 1_000L, subId = 1)
            env.source.rows += provider(2, "after checkpoint", 2_000L, subId = 2)
            // Simulate an interrupted earlier run that had committed through
            // provider id 1: the resumed pass must not rescan it.
            env.dataStore.edit { it[SimBackfill.KEY_LAST_PROVIDER_ID] = 1L }

            assertThat(env.backfill.runIfNeeded()).isEqualTo(1)

            val all = env.db.messageDao().getAll()
            assertThat(all.single { it.systemSmsId == 1L }.subscriptionId).isNull()
            assertThat(all.single { it.systemSmsId == 2L }.subscriptionId).isEqualTo(2)
            // Completion sets the version marker and clears the checkpoint.
            val prefs = env.dataStore.data.first()
            assertThat(prefs[SimBackfill.KEY_DONE_VERSION]).isEqualTo(SimBackfill.VERSION)
            assertThat(prefs[SimBackfill.KEY_LAST_PROVIDER_ID]).isNull()
        }
}
