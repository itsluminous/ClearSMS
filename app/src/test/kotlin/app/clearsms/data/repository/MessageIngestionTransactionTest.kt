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
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.IOException

@RunWith(RobolectricTestRunner::class)
class MessageIngestionTransactionTest {
    private lateinit var db: ClearSmsDatabase
    private lateinit var repository: MessageRepositoryImpl

    /** A real bank debit SMS that yields message + transaction + account rows. */
    private val debitBody =
        "Rs.250.00 debited from A/c XX9805 to VPA merchant@okicici on 20-07-26. Ref No 020520123456. Avl Bal Rs.5,000.25 - ICICI Bank."
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
            )
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun `ingestion writes message and derived finance rows together`() =
        runBlocking {
            val inserted = repository.insertIncoming(debitSender, debitBody, 1_000L)

            assertThat(inserted.id).isGreaterThan(0L)
            assertThat(db.messageDao().getAll()).hasSize(1)
            assertThat(db.transactionDao().getAll()).hasSize(1)
            assertThat(
                db
                    .transactionDao()
                    .getAll()
                    .single()
                    .rawSmsId,
            ).isEqualTo(inserted.id)
        }

    @Test
    fun `failure after derivation rolls back the message too`() =
        runBlocking {
            repository.ingestionFailpointForTest = { throw IOException("disk died mid-derivation") }

            assertThrows(IOException::class.java) {
                runBlocking { repository.insertIncoming(debitSender, debitBody, 1_000L) }
            }

            // Nothing half-written: no orphan message, transaction or account.
            assertThat(db.messageDao().getAll()).isEmpty()
            assertThat(db.transactionDao().getAll()).isEmpty()
            assertThat(db.accountDao().getAll()).isEmpty()
        }

    @Test
    fun `retry after failure ingests exactly one consistent unit`() =
        runBlocking {
            repository.ingestionFailpointForTest = { throw IOException("first attempt fails") }
            assertThrows(IOException::class.java) {
                runBlocking { repository.insertIncoming(debitSender, debitBody, 1_000L) }
            }

            repository.ingestionFailpointForTest = null
            repository.insertIncoming(debitSender, debitBody, 1_000L)

            assertThat(db.messageDao().getAll()).hasSize(1)
            assertThat(db.transactionDao().getAll()).hasSize(1)
        }

    @Test
    fun `re-processing an imported page is a no-op`() =
        runBlocking {
            val snapshot = repository.rulesSnapshot()
            val page =
                listOf(
                    ImportedSmsRow(
                        systemSmsId = 42L,
                        sender = debitSender,
                        body = debitBody,
                        timestampMs = 1_000L,
                        isRead = true,
                        enriched = repository.classify(snapshot, debitSender, debitBody),
                    ),
                )

            val first = repository.persistImportedPage(page)
            val second = repository.persistImportedPage(page)

            assertThat(first).isEqualTo(1)
            assertThat(second).isEqualTo(0)
            assertThat(db.messageDao().getAll()).hasSize(1)
            assertThat(db.transactionDao().getAll()).hasSize(1)
        }

    private object NoopDataStore : DataStore<Preferences> {
        override val data: Flow<Preferences> = flowOf(emptyPreferences())

        override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences): Preferences = emptyPreferences()
    }
}
