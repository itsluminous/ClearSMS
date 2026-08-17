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
 * Blocked-keyword ingestion semantics: a matching incoming message is born
 * soft-deleted (recycle bin on) or never written (bin off), removes its
 * system-provider copy, and derives NOTHING - no transaction, account,
 * reminder, or inbox visibility. Non-matching messages are untouched.
 */
@RunWith(RobolectricTestRunner::class)
class KeywordBlockingIngestionTest {
    private lateinit var db: ClearSmsDatabase
    private val json = Json { ignoreUnknownKeys = true }
    private val deletedSystemIds = mutableListOf<Long>()
    private var keywords = setOf("loan offer")
    private var binEnabled = true

    private object NoopStore : DataStore<Preferences> {
        override val data: Flow<Preferences> = flowOf(emptyPreferences())

        override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences): Preferences = emptyPreferences()
    }

    private lateinit var repository: MessageRepositoryImpl

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
                bundledRuleLoader = BundledRuleLoader(context, db.ruleDao(), json, NoopStore),
                json = json,
                systemSmsDeleter = { ids ->
                    deletedSystemIds += ids
                    ids.size
                },
                blockedKeywords = { keywords },
                recycleBinEnabled = { binEnabled },
            )
    }

    @After
    fun tearDown() {
        db.close()
    }

    /** A transaction-bearing promo shape: without blocking it would derive finance rows. */
    private val matchingBody =
        "Exclusive LOAN OFFER! Rs. 500000 pre-approved. Reply YES. T&C apply."

    @Test
    fun `keyword match with bin on lands in the bin with zero derived rows`() =
        runBlocking {
            val ingest = repository.ingestIncoming("BX-SPAMCO", matchingBody, 1_000L, systemSmsId = 42L)

            assertThat(ingest.entity.deletedAt).isNotNull()
            // Never inbox-visible, present in the bin.
            assertThat(repository.observeInbox(null, false).first()).isEmpty()
            assertThat(repository.observeBin().first()).hasSize(1)
            // No finance derivations of any kind.
            assertThat(db.transactionDao().getAll()).isEmpty()
            assertThat(db.accountDao().getAll()).isEmpty()
            assertThat(db.reminderDao().getAll()).isEmpty()
            // The provider copy is removed like a committed delete.
            assertThat(deletedSystemIds).containsExactly(42L)
            // And it never counts as unread.
            assertThat(repository.observeUnreadCounts().first()).isEmpty()
        }

    @Test
    fun `keyword match with bin off drops the row entirely`() =
        runBlocking<Unit> {
            binEnabled = false
            val ingest = repository.ingestIncoming("BX-SPAMCO", matchingBody, 1_000L, systemSmsId = 43L)

            assertThat(ingest.entity.deletedAt).isNotNull()
            assertThat(db.messageDao().getAll()).isEmpty()
            assertThat(repository.observeBin().first()).isEmpty()
            assertThat(deletedSystemIds).containsExactly(43L)
        }

    @Test
    fun `matching is case-insensitive at ingestion`() =
        runBlocking {
            repository.ingestIncoming("BX-SPAMCO", "special lOaN oFfEr today", 1_000L)

            assertThat(repository.observeInbox(null, false).first()).isEmpty()
            assertThat(repository.observeBin().first()).hasSize(1)
        }

    @Test
    fun `non-matching messages ingest normally`() =
        runBlocking {
            val ingest = repository.ingestIncoming("AX-HDFCBK", "Your a/c is credited with Rs 100", 1_000L)

            assertThat(ingest.entity.deletedAt).isNull()
            assertThat(repository.observeInbox(null, false).first()).hasSize(1)
            assertThat(repository.observeBin().first()).isEmpty()
        }

    @Test
    fun `the import path bins matching rows and skips their derivations`() =
        runBlocking<Unit> {
            val snapshot = repository.rulesSnapshot()
            val rows =
                listOf(
                    ImportedSmsRow(
                        systemSmsId = 7L,
                        sender = "BX-SPAMCO",
                        body = matchingBody,
                        timestampMs = 1_000L,
                        isRead = false,
                        enriched = repository.classify(snapshot, "BX-SPAMCO", matchingBody, 1_000L),
                    ),
                    ImportedSmsRow(
                        systemSmsId = 8L,
                        sender = "AX-FRIEND",
                        body = "See you at 6?",
                        timestampMs = 2_000L,
                        isRead = false,
                        enriched = repository.classify(snapshot, "AX-FRIEND", "See you at 6?", 1_000L),
                    ),
                )

            val inserted = repository.persistImportedPage(rows)

            // Both rows inserted; the matching one is born-deleted so the
            // catch-up fresh filter (deletedAt != null) never notifies it.
            assertThat(inserted).hasSize(2)
            val binned = inserted.first { it.systemSmsId == 7L }
            assertThat(binned.deletedAt).isNotNull()
            assertThat(inserted.first { it.systemSmsId == 8L }.deletedAt).isNull()
            assertThat(repository.observeInbox(null, false).first()).hasSize(1)
            assertThat(repository.observeBin().first()).hasSize(1)
            assertThat(db.transactionDao().getAll()).isEmpty()
            assertThat(deletedSystemIds).containsExactly(7L)
        }

    @Test
    fun `the import path drops matching rows when the bin is off`() =
        runBlocking<Unit> {
            binEnabled = false
            val snapshot = repository.rulesSnapshot()
            val rows =
                listOf(
                    ImportedSmsRow(
                        systemSmsId = 9L,
                        sender = "BX-SPAMCO",
                        body = matchingBody,
                        timestampMs = 1_000L,
                        isRead = false,
                        enriched = repository.classify(snapshot, "BX-SPAMCO", matchingBody, 1_000L),
                    ),
                )

            val inserted = repository.persistImportedPage(rows)

            assertThat(inserted).isEmpty()
            assertThat(db.messageDao().getAll()).isEmpty()
            assertThat(deletedSystemIds).containsExactly(9L)
        }
}
