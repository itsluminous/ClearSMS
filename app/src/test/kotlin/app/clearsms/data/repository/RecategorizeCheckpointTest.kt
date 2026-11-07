package app.clearsms.data.repository

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import app.clearsms.data.db.ClearSmsDatabase
import app.clearsms.data.rules.BundledRuleLoader
import app.clearsms.data.rules.RuleEngine
import app.clearsms.domain.categorizer.ContactLookup
import app.clearsms.domain.categorizer.MessageCategorizer
import app.clearsms.domain.categorizer.SenderIdLookup
import app.clearsms.domain.model.Category
import app.clearsms.testing.InMemoryPreferencesDataStore
import app.clearsms.work.RecategorizeCheckpointPrefs
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * A stopped full re-sort resumes after its last committed page instead of
 * starting over (issue #153: the restart-from-zero on every process start
 * is what turned a one-off post-update sort into a per-receive one), and
 * the checkpoint carries what the orphan-account sweep needs.
 */
@RunWith(RobolectricTestRunner::class)
class RecategorizeCheckpointTest {
    private lateinit var db: ClearSmsDatabase
    private lateinit var repository: MessageRepositoryImpl
    private val json = Json { ignoreUnknownKeys = true }

    /** In-memory store recording every write, so a test can see the checkpoint sequence. */
    private class RecordingStore : RecategorizeCheckpointStore {
        var stored: RecategorizeCheckpoint? = null
        val writes = mutableListOf<RecategorizeCheckpoint>()
        var clears = 0

        override suspend fun get(): RecategorizeCheckpoint? = stored

        override suspend fun set(checkpoint: RecategorizeCheckpoint) {
            stored = checkpoint
            writes += checkpoint
        }

        override suspend fun clear() {
            stored = null
            clears++
        }
    }

    private val debitBody =
        "UPDATE: INR 13,000.00 debited from HDFC Bank XX8709 on 16-JUL-26. " +
            "Info: XXXXXXXXXX6894- RD Installment-Jul 2026. Avl bal:INR 1,07,721.74"

    /** Balance-ONLY statement: creates/refreshes an account, never a transaction. */
    private val balanceBody =
        "Available Bal in HDFC Bank A/c XX4321 as on yesterday:27-JUL-26 is INR 40,194.56. " +
            "Cheques are subject to clearing.For updated A/C Bal dial 18002703333."

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db =
            Room
                .inMemoryDatabaseBuilder(context, ClearSmsDatabase::class.java)
                .allowMainThreadQueries()
                .build()
        repository =
            MessageRepositoryImpl(
                database = db,
                categorizer =
                    MessageCategorizer(
                        ruleEngine = RuleEngine(),
                        senderIdLookup = SenderIdLookup { null },
                        contactLookup = ContactLookup { false },
                    ),
                bundledRuleLoader = BundledRuleLoader(context, db.ruleDao(), json, InMemoryPreferencesDataStore()),
                json = json,
                // Two rows per page so a handful of messages spans several pages.
                recategorizePageSize = 2,
            )
    }

    @After
    fun tearDown() {
        db.close()
    }

    private suspend fun seedPersonal(count: Int): List<Long> =
        (0 until count).map { i ->
            repository.insertIncoming("+9198765432$i", "hey $i", 1_000L + i * 100_000L).id
        }

    /** Runs a re-sort against [store] and cancels it right after the first committed page. */
    private suspend fun runAndStopAfterFirstPage(store: RecategorizeCheckpointStore) =
        coroutineScope {
            lateinit var job: Job
            job =
                launch {
                    repository.recategorizeAll(store) { processed, _ ->
                        if (processed >= 2) job.cancel()
                    }
                }
            job.join()
            assertThat(job.isCancelled).isTrue()
        }

    @Test
    fun `a stopped run resumes after the last committed page instead of starting over`() =
        runBlocking {
            val ids = seedPersonal(6)
            val store = RecordingStore()
            runAndStopAfterFirstPage(store)
            // One page committed: the checkpoint names its last row.
            assertThat(store.stored).isNotNull()
            assertThat(store.stored!!.afterId).isEqualTo(ids[1])
            assertThat(store.stored!!.processed).isEqualTo(2)

            // Tamper with a row INSIDE the committed page: a run that starts
            // over would re-classify it back to PERSONAL; a resumed run
            // leaves it alone.
            val first = db.messageDao().getById(ids[0])!!
            db.messageDao().update(first.copy(category = Category.SPAM))
            // And with a row AFTER the checkpoint: the resumed run must still
            // reach and re-classify that one back to the rules' verdict.
            val fifth = db.messageDao().getById(ids[4])!!
            val verdict = fifth.category
            assertThat(verdict).isNotEqualTo(Category.SPAM)
            db.messageDao().update(fifth.copy(category = Category.SPAM))

            val progress = mutableListOf<Pair<Int, Int>>()
            val processed = repository.recategorizeAll(store) { p, t -> progress += p to t }

            assertThat(progress.first()).isEqualTo(2 to 6)
            assertThat(progress.last()).isEqualTo(6 to 6)
            assertThat(processed).isEqualTo(6)
            assertThat(db.messageDao().getById(ids[0])!!.category).isEqualTo(Category.SPAM)
            assertThat(db.messageDao().getById(ids[4])!!.category).isEqualTo(verdict)
            // A completed pass leaves no checkpoint behind.
            assertThat(store.stored).isNull()
            assertThat(store.clears).isEqualTo(1)
        }

    @Test
    fun `the checkpoint advances once per committed page and is cleared on completion`() =
        runBlocking {
            val ids = seedPersonal(5)
            val store = RecordingStore()

            repository.recategorizeAll(store)

            assertThat(store.writes.map { it.afterId }).containsExactly(ids[1], ids[3], ids[4]).inOrder()
            assertThat(store.writes.map { it.processed }).containsExactly(2, 4, 5).inOrder()
            assertThat(store.stored).isNull()
        }

    @Test
    fun `without a store the run is a plain full pass`() =
        runBlocking {
            seedPersonal(3)
            val progress = mutableListOf<Int>()
            assertThat(repository.recategorizeAll { p, _ -> progress += p }).isEqualTo(3)
            assertThat(progress).containsExactly(0, 2, 3).inOrder()
        }

    @Test
    fun `accounts touched before the stop survive the sweep of the resumed run`() =
        runBlocking<Unit> {
            // Page 1 holds the balance-only statement: its account has NO
            // transaction, so only the pass's own "touch" protects it from
            // the orphan sweep. The stop lands between page 1 and page 2.
            repository.insertIncoming("VM-HDFCBK", balanceBody, 1_000L)
            repository.insertIncoming("+919876543200", "hey", 1_100L)
            repository.insertIncoming("VM-HDFCBK", debitBody, 1_200L)
            repository.insertIncoming("+919876543201", "hey again", 1_300L)
            assertThat(db.accountDao().getAll().map { it.accountNumber }).containsExactly("4321", "8709")

            val store = RecordingStore()
            runAndStopAfterFirstPage(store)
            assertThat(store.stored!!.touchedAccounts).isNotEmpty()

            repository.recategorizeAll(store)

            // Both accounts survive: 8709 through its transaction, 4321 only
            // because the resumed run inherited the first page's touch.
            assertThat(db.accountDao().getAll().map { it.accountNumber }).containsExactly("4321", "8709")
        }

    @Test
    fun `the durable store round-trips a checkpoint and discards one from another app version`() =
        runBlocking {
            val prefs = RecategorizeCheckpointPrefs(InMemoryPreferencesDataStore())
            prefs.versionCode = 41
            val checkpoint = RecategorizeCheckpoint(afterId = 120L, processed = 100, touchedAccounts = setOf(3L, 7L))

            prefs.set(checkpoint)
            assertThat(prefs.get()).isEqualTo(checkpoint)

            // The app updated: AutoResortScheduler will enqueue a full
            // re-sort for the new version, and it must run from the first
            // row - the first 100 messages were sorted with the OLD rules.
            prefs.versionCode = 42
            assertThat(prefs.get()).isNull()
            // ...and the stale record is gone for good, not merely hidden.
            prefs.versionCode = 41
            assertThat(prefs.get()).isNull()

            prefs.set(checkpoint)
            prefs.clear()
            assertThat(prefs.get()).isNull()
        }
}
