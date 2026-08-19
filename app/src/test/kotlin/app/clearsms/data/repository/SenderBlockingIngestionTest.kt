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
 * Blocked-SENDER ingestion semantics, the sibling of
 * [KeywordBlockingIngestionTest]: an incoming message from a sender on the
 * authoritative blocklist set is born soft-deleted (recycle bin on) or
 * never written (bin off), removes its system-provider copy, carries the
 * derived `isBlockedSender` flag, and derives NOTHING. The set - not the
 * rows - is the authority, so the block survives deleting the thread, and
 * matching is normalization-aware ("VM-JIOPAY" == "JIOPAY").
 */
@RunWith(RobolectricTestRunner::class)
class SenderBlockingIngestionTest {
    private lateinit var db: ClearSmsDatabase
    private val json = Json { ignoreUnknownKeys = true }
    private val deletedSystemIds = mutableListOf<Long>()
    private var blockedSenders = emptySet<String>()
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
                blockedKeywords = { setOf("loan offer") },
                blockedSenders = { blockedSenders },
                recycleBinEnabled = { binEnabled },
            )
    }

    @After
    fun tearDown() {
        db.close()
    }

    /** A transaction-bearing shape: without blocking it would derive finance rows. */
    private val financeBody = "Rs.900 debited from A/c XX1111 on 01-01-25. Avl bal Rs.5000."

    @Test
    fun `blocked sender with bin on lands born-deleted with zero derived rows`() =
        runBlocking {
            blockedSenders = setOf("SPAMCO")

            val ingest = repository.ingestIncoming("BX-SPAMCO", financeBody, 1_000L, systemSmsId = 42L)

            assertThat(ingest.entity.deletedAt).isNotNull()
            assertThat(ingest.entity.isBlockedSender).isTrue()
            assertThat(ingest.entity.isRead).isTrue()
            // Never inbox-visible, resting in the bin.
            assertThat(repository.observeInbox(null, false).first()).isEmpty()
            assertThat(repository.observeBin().first()).hasSize(1)
            // No finance derivations of any kind.
            assertThat(db.transactionDao().getAll()).isEmpty()
            assertThat(db.accountDao().getAll()).isEmpty()
            assertThat(db.reminderDao().getAll()).isEmpty()
            // The provider copy is removed like a committed delete.
            assertThat(deletedSystemIds).containsExactly(42L)
            assertThat(repository.observeUnreadCounts().first()).isEmpty()
        }

    @Test
    fun `blocked sender with bin off drops the row entirely`() =
        runBlocking<Unit> {
            blockedSenders = setOf("SPAMCO")
            binEnabled = false

            val ingest = repository.ingestIncoming("BX-SPAMCO", financeBody, 1_000L, systemSmsId = 43L)

            assertThat(ingest.entity.deletedAt).isNotNull()
            assertThat(db.messageDao().getAll()).isEmpty()
            assertThat(repository.observeBin().first()).isEmpty()
            assertThat(deletedSystemIds).containsExactly(43L)
        }

    @Test
    fun `matching normalizes both sides - raw variant in the set blocks the bare id and vice versa`() =
        runBlocking {
            // Raw variant stored (a hand-typed dialog entry): still matches.
            blockedSenders = setOf("VM-JIOPAY")
            assertThat(repository.ingestIncoming("JIOPAY", "50% off!", 1_000L).entity.deletedAt).isNotNull()

            // Normalized entry blocks every route variant of the sender.
            blockedSenders = setOf("JIOPAY")
            assertThat(repository.ingestIncoming("VM-JIOPAY-S", "60% off!", 2_000L).entity.deletedAt).isNotNull()
            assertThat(repository.ingestIncoming("AD-JIOPAY", "70% off!", 3_000L).entity.deletedAt).isNotNull()
        }

    @Test
    fun `the block is set-authoritative and survives deleting the sender's thread`() =
        runBlocking {
            blockedSenders = setOf("SPAMCO")
            val first = repository.ingestIncoming("BX-SPAMCO", "hello", 1_000L).entity
            // Delete forever: the old EXISTS-over-rows authority would lose
            // the block here, because no flagged row remains.
            repository.deleteForever(listOf(first.id))
            assertThat(db.messageDao().getAll()).isEmpty()

            val second = repository.ingestIncoming("BX-SPAMCO", "hello again", 2_000L).entity

            assertThat(second.deletedAt).isNotNull()
            assertThat(second.isBlockedSender).isTrue()
        }

    @Test
    fun `unblocked senders land normally - the set is consulted per ingest`() =
        runBlocking {
            blockedSenders = setOf("SPAMCO")
            repository.ingestIncoming("BX-SPAMCO", "binned", 1_000L)
            blockedSenders = emptySet()

            val after = repository.ingestIncoming("BX-SPAMCO", "visible", 2_000L).entity

            assertThat(after.deletedAt).isNull()
            assertThat(after.isBlockedSender).isFalse()
            assertThat(repository.observeInbox(null, false).first()).hasSize(1)
            // The earlier binned message stays binned: unblocking never restores.
            assertThat(repository.observeBin().first()).hasSize(1)
        }

    @Test
    fun `keyword blocking is unregressed and combines with sender blocking`() =
        runBlocking {
            // Keyword-only match from a NOT-blocked sender: keyword semantics.
            val keyword = repository.ingestIncoming("BX-OTHERCO", "special LOAN OFFER now", 1_000L).entity
            assertThat(keyword.deletedAt).isNotNull()
            assertThat(keyword.isBlockedSender).isFalse()

            // Blocked sender AND keyword match: born-deleted, flag carried.
            blockedSenders = setOf("SPAMCO")
            val both = repository.ingestIncoming("BX-SPAMCO", "special LOAN OFFER now", 2_000L).entity
            assertThat(both.deletedAt).isNotNull()
            assertThat(both.isBlockedSender).isTrue()
        }

    @Test
    fun `import path bins a blocked sender's history - incoming and outgoing - deriving nothing`() =
        runBlocking<Unit> {
            blockedSenders = setOf("SPAMCO")
            val snapshot = repository.rulesSnapshot()
            val page =
                listOf(
                    ImportedSmsRow(
                        systemSmsId = 1L,
                        sender = "BX-SPAMCO",
                        body = financeBody,
                        timestampMs = 1_000L,
                        isRead = false,
                        enriched = repository.classify(snapshot, "BX-SPAMCO", financeBody, 1_000L),
                        delivered = false,
                    ),
                    // Outgoing message TO the blocked sender: binned with the
                    // thread, so no ghost thread of sent messages remains.
                    ImportedSmsRow(
                        systemSmsId = 2L,
                        sender = "BX-SPAMCO",
                        body = "my reply",
                        timestampMs = 2_000L,
                        isRead = true,
                        enriched = null,
                        delivered = true,
                    ),
                    ImportedSmsRow(
                        systemSmsId = 3L,
                        sender = "OKBANK",
                        body = "regular message",
                        timestampMs = 3_000L,
                        isRead = false,
                        enriched = repository.classify(snapshot, "OKBANK", "regular message", 3_000L),
                        delivered = false,
                    ),
                )

            val inserted = repository.persistImportedPage(page)

            assertThat(inserted).hasSize(3)
            val blockedRows = inserted.filter { it.normalizedSender == "SPAMCO" }
            assertThat(blockedRows).hasSize(2)
            blockedRows.forEach { row ->
                assertThat(row.isBlockedSender).isTrue()
                assertThat(row.deletedAt).isNotNull()
            }
            // The catch-up fresh filter skips blocked/binned rows via these
            // exact fields (see SystemSmsImporter), so nothing notifies.
            assertThat(inserted.single { it.normalizedSender == "OKBANK" }.deletedAt).isNull()
            assertThat(repository.observeInbox(null, false).first()).hasSize(1)
            assertThat(repository.observeBin().first()).hasSize(2)
            // Blocked history derives nothing.
            assertThat(db.transactionDao().getAll()).isEmpty()
            // Provider copies of the binned rows were removed.
            assertThat(deletedSystemIds).containsExactly(1L, 2L)
        }

    @Test
    fun `a message reusing a binned message's provider id is NOT lost`() =
        runBlocking {
            // The live emulator failure: a blocked sender's thread is binned
            // (provider copies deleted, ids freed), then the provider hands a
            // freed id to the next arrival. Treating "same provider id" as
            // "same message" discarded it - present in the system provider,
            // absent from the app, no notification, no trace.
            // A normal (visible) message keeps its provider id 42.
            val first = repository.ingestIncoming("SPAMCO", "visible message", 1_000L, systemSmsId = 42L)
            assertThat(db.messageDao().bySystemSmsId(42L)).isNotNull()

            // The user blocks the sender: the thread goes to the bin and its
            // provider copies are deleted, freeing provider id 42.
            blockedSenders = setOf("SPAMCO")
            repository.commitStagedDelete(listOf(first.entity.id), toBin = true)

            // The provider hands the freed id 42 to the next arrival.
            val ingest = repository.ingestIncoming("SPAMCO", "second blocked message", 2_000L, systemSmsId = 42L)

            assertThat(ingest.duplicate).isFalse()
            assertThat(ingest.entity.body).isEqualTo("second blocked message")
            // Both messages survive, each resting in the bin, and neither still
            // claims id 42 (a born-deleted message's provider copy is deleted
            // at once, so it releases the id immediately).
            val stored = db.messageDao().getById(ingest.entity.id)!!
            assertThat(stored.deletedAt).isNotNull()
            assertThat(stored.systemSmsId).isNull()
            assertThat(db.messageDao().getById(first.entity.id)!!.body).isEqualTo("visible message")
        }

    @Test
    fun `an unblocked sender's message reusing a binned provider id is NOT lost`() =
        runBlocking {
            // Same hazard on the ordinary path: bin any message (its provider
            // copy goes), and the next arrival may inherit that provider id.
            repository.ingestIncoming("HDFCBK", "old message", 1_000L, systemSmsId = 7L)
            val old = db.messageDao().bySystemSmsId(7L)!!
            repository.commitStagedDelete(listOf(old.id), toBin = true)

            val ingest = repository.ingestIncoming("HDFCBK", "new message", 2_000L, systemSmsId = 7L)

            assertThat(ingest.duplicate).isFalse()
            assertThat(db.messageDao().bySystemSmsId(7L)!!.body).isEqualTo("new message")
        }

    @Test
    fun `a true redelivery of the same message is still deduplicated`() =
        runBlocking {
            repository.ingestIncoming("HDFCBK", "same body", 3_000L, systemSmsId = 9L)

            val again = repository.ingestIncoming("HDFCBK", "same body", 3_000L, systemSmsId = 9L)

            assertThat(again.duplicate).isTrue()
            assertThat(again.entity.id).isEqualTo(db.messageDao().bySystemSmsId(9L)!!.id)
        }
}
