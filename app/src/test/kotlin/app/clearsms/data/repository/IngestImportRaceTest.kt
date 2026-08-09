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
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The `systemSmsId` race between a live delivery and a concurrent catch-up
 * import (signal returns → receiver writes the provider row → the user opens
 * the app → the gap probe's import commits the Room row first). Whichever
 * path loses must become a no-op on the row so exactly one of the two
 * notifies: the receiver skips when [MessageRepository.IncomingIngest.duplicate]
 * is set (the import sees the row as post-watermark and notifies it), and the
 * import's IGNORE insert skips rows the receiver committed first (which the
 * receiver already notified live).
 */
@RunWith(RobolectricTestRunner::class)
class IngestImportRaceTest {
    private lateinit var db: ClearSmsDatabase
    private lateinit var repository: MessageRepositoryImpl

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

    private suspend fun importedRow(systemSmsId: Long) =
        ImportedSmsRow(
            systemSmsId = systemSmsId,
            sender = debitSender,
            body = debitBody,
            timestampMs = 5_000L,
            isRead = false,
            enriched = repository.classify(repository.rulesSnapshot(), debitSender, debitBody),
            delivered = false,
        )

    @Test
    fun `plain ingest reports no duplicate`() =
        runBlocking {
            val ingest = repository.ingestIncoming(debitSender, debitBody, 5_000L, systemSmsId = 42L)
            assertThat(ingest.duplicate).isFalse()
            assertThat(db.messageDao().getAll()).hasSize(1)
        }

    @Test
    fun `live delivery losing the race returns the imported row marked duplicate`() =
        runBlocking {
            // Import commits the provider row first...
            val imported = repository.persistImportedPage(listOf(importedRow(42L))).single()
            val transactionsAfterImport = db.transactionDao().getAll()

            // ...then the receiver's ingest hits the unique index.
            val ingest = repository.ingestIncoming(debitSender, debitBody, 5_000L, systemSmsId = 42L)

            assertThat(ingest.duplicate).isTrue()
            assertThat(ingest.entity.id).isEqualTo(imported.id)
            // No second message row, no doubled finance rows.
            assertThat(db.messageDao().getAll()).hasSize(1)
            assertThat(db.transactionDao().getAll()).isEqualTo(transactionsAfterImport)
        }

    @Test
    fun `import losing the race to a live delivery skips the row entirely`() =
        runBlocking {
            val ingest = repository.ingestIncoming(debitSender, debitBody, 5_000L, systemSmsId = 42L)
            assertThat(ingest.duplicate).isFalse()
            val transactionsAfterIngest = db.transactionDao().getAll()

            // The import's page insert is IGNOREd: nothing fresh to notify.
            val inserted = repository.persistImportedPage(listOf(importedRow(42L)))

            assertThat(inserted).isEmpty()
            assertThat(db.messageDao().getAll()).hasSize(1)
            assertThat(db.transactionDao().getAll()).isEqualTo(transactionsAfterIngest)
        }

    private object NoopDataStore : DataStore<Preferences> {
        override val data: Flow<Preferences> = flowOf(emptyPreferences())

        override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences): Preferences = emptyPreferences()
    }
}
