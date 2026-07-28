package app.clearsms.data.repository

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import app.clearsms.data.db.ClearSmsDatabase
import app.clearsms.data.rules.BundledRuleLoader
import app.clearsms.data.rules.RuleEngine
import app.clearsms.domain.categorizer.ContactLookup
import app.clearsms.domain.categorizer.MessageCategorizer
import app.clearsms.domain.categorizer.SenderIdLookup
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Progress, idempotency and cancellation of the paged manual re-sort
 * (`recategorizeAll`): the processed/total sequence follows page boundaries,
 * repeated runs never duplicate finance/reminder rows, and a cancelled run
 * leaves the database consistent.
 */
@RunWith(RobolectricTestRunner::class)
class RecategorizeProgressTest {
    private lateinit var db: ClearSmsDatabase
    private lateinit var repository: MessageRepositoryImpl

    private val debitSender = "VM-ICICIB"

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
                // Tiny pages so tests exercise batch boundaries cheaply.
                recategorizePageSize = 2,
            )
    }

    @After
    fun tearDown() {
        db.close()
    }

    private fun seed(count: Int) =
        runBlocking {
            repeat(count) { i ->
                // Genuinely DISTINCT transactions: unique amount and reference,
                // and timestamps spaced well beyond the dedup window, so each
                // message yields its own transaction row (this test validates
                // re-sort idempotency, not deduplication).
                val body =
                    "Rs.${250 + i}.00 debited from A/c XX9805 to VPA merchant@okicici on 20-07-26. " +
                        "Ref No 02052012345$i. Avl Bal Rs.5,000.25 - ICICI Bank."
                repository.insertIncoming(debitSender, body, 1_000L + i * 200_000L)
            }
        }

    @Test
    fun `progress is emitted per page and ends at total`() =
        runBlocking {
            seed(5)
            val progress = mutableListOf<Pair<Int, Int>>()

            val count = repository.recategorizeAll { processed, total -> progress += processed to total }

            // Page size 2 over 5 rows: initial tick then one per committed page.
            assertThat(progress).containsExactly(0 to 5, 2 to 5, 4 to 5, 5 to 5).inOrder()
            assertThat(count).isEqualTo(5)
        }

    @Test
    fun `completion count reports every message processed`() =
        runBlocking {
            seed(3)
            assertThat(repository.recategorizeAll()).isEqualTo(3)
        }

    @Test
    fun `re-sorting twice duplicates neither messages nor derived rows`() =
        runBlocking {
            seed(3)
            val messagesBefore = db.messageDao().getAll().size
            val transactionsBefore = db.transactionDao().getAll().size
            val remindersBefore = db.reminderDao().getAll().size
            assertThat(transactionsBefore).isEqualTo(3)

            repository.recategorizeAll()
            repository.recategorizeAll()

            assertThat(db.messageDao().getAll()).hasSize(messagesBefore)
            assertThat(db.transactionDao().getAll()).hasSize(transactionsBefore)
            assertThat(db.reminderDao().getAll()).hasSize(remindersBefore)
        }

    @Test
    fun `cancellation mid-run leaves the database consistent`() =
        runBlocking {
            seed(5)
            val transactionsBefore = db.transactionDao().getAll().size

            lateinit var job: kotlinx.coroutines.Job
            job =
                launch {
                    repository.recategorizeAll { processed, _ ->
                        // Cancel after the first committed page.
                        if (processed >= 2) job.cancel()
                    }
                }
            job.join()

            assertThat(job.isCancelled).isTrue()
            // No rows lost, none duplicated: committed pages are atomic units.
            assertThat(db.messageDao().getAll()).hasSize(5)
            assertThat(db.transactionDao().getAll()).hasSize(transactionsBefore)
            db.messageDao().getAll().forEach { message ->
                assertThat(db.transactionDao().findByRawSmsId(message.id)).isNotNull()
            }
        }

    private object NoopDataStore : DataStore<Preferences> {
        override val data: Flow<Preferences> = flowOf(emptyPreferences())

        override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences): Preferences = emptyPreferences()
    }
}
