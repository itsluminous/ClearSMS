package app.clearsms.sms

import android.app.NotificationManager
import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.provider.Telephony
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import app.clearsms.data.db.ClearSmsDatabase
import app.clearsms.data.repository.MessageRepositoryImpl
import app.clearsms.data.rules.BundledRuleLoader
import app.clearsms.data.rules.RuleEngine
import app.clearsms.domain.categorizer.MessageCategorizer
import app.clearsms.domain.model.Category
import app.clearsms.work.SyncCheckpointStore
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

/**
 * End-to-end tests for the resumable, idempotent, parallel history import,
 * backed by a fake `content://sms` provider and an in-memory Room database.
 */
@RunWith(RobolectricTestRunner::class)
class SystemSmsImporterTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val json = Json { ignoreUnknownKeys = true }
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val openDbs = mutableListOf<ClearSmsDatabase>()

    /** One isolated import environment (db + checkpoint + importer). */
    private inner class Env(
        name: String,
    ) {
        val db: ClearSmsDatabase =
            Room
                .inMemoryDatabaseBuilder(context, ClearSmsDatabase::class.java)
                .allowMainThreadQueries()
                .build()
                .also { openDbs += it }
        private val dataStore =
            PreferenceDataStoreFactory.create(scope = scope) {
                tmp.newFile("$name.preferences_pb")
            }
        val repository =
            MessageRepositoryImpl(
                database = db,
                categorizer =
                    MessageCategorizer(
                        ruleEngine = RuleEngine(),
                        senderIdLookup = { null },
                        contactLookup = { false },
                    ),
                bundledRuleLoader = BundledRuleLoader(context, db.ruleDao(), json, dataStore),
                json = json,
            )
        val checkpoints = SyncCheckpointStore(dataStore)
        val importer = SystemSmsImporter(context, repository, checkpoints, Dispatchers.IO)
    }

    @Before
    fun setUp() {
        FakeSmsProvider.rows.clear()
        Robolectric.setupContentProvider(FakeSmsProvider::class.java, "sms")
    }

    @After
    fun tearDown() {
        openDbs.forEach { it.close() }
        scope.cancel()
    }

    // region fixtures

    private fun otpBody(i: Int) = "Your OTP is ${1000 + i} for the booking. Valid for 10 minutes."

    private fun txnBody(i: Int) =
        "Sent Rs.${100 + i}.00 From HDFC Bank A/C x1234 To SWIGGY On 12/07/26 Ref 5199123456$i Not You? Call 18002586161"

    private fun addInbox(
        id: Long,
        address: String,
        body: String,
        read: Boolean = false,
    ) {
        FakeSmsProvider.rows +=
            FakeSmsProvider.Row(id, address, body, date = 1_700_000_000_000 + id * 1000, type = 1, read = if (read) 1 else 0)
    }

    private fun addSent(
        id: Long,
        address: String,
        body: String,
    ) {
        FakeSmsProvider.rows +=
            FakeSmsProvider.Row(id, address, body, date = 1_700_000_000_000 + id * 1000, type = 2, read = 1)
    }

    /** Deterministic mixed workload: OTPs, transactions and plain texts. */
    private fun addMixedRows(range: LongRange) {
        for (id in range) {
            when ((id % 4).toInt()) {
                0 -> addInbox(id, "VM-HDFCBK", txnBody(id.toInt()))
                1 -> addInbox(id, "AX-BOOKMY", otpBody(id.toInt()))
                2 -> addInbox(id, "98765432${id % 10}0", "hey, are we still on for message $id?", read = true)
                else -> addSent(id, "9876543210", "sure, see you then ($id)")
            }
        }
    }

    // endregion

    @Test
    fun `imports inbox and sent messages and skips drafts`() =
        runBlocking {
            addInbox(1, "AX-BOOKMY", otpBody(1))
            addInbox(2, "VM-HDFCBK", txnBody(2))
            addInbox(3, "9876543210", "hello there", read = true)
            addSent(4, "9876543210", "hi!")
            FakeSmsProvider.rows += FakeSmsProvider.Row(5, "9876543210", "unsent draft", 1_700_000_005_000, type = 3, read = 0)

            val env = Env("basic")
            val inserted = env.importer.importAll().inserted

            val messages = env.db.messageDao().getAll()
            assertThat(inserted).isEqualTo(4)
            assertThat(messages).hasSize(4)

            val otp = messages.single { it.systemSmsId == 1L }
            assertThat(otp.category).isEqualTo(Category.OTP)
            assertThat(otp.extractedOtp).isEqualTo("1001")

            val txn = messages.single { it.systemSmsId == 2L }
            assertThat(txn.category).isEqualTo(Category.IMPORTANT)
            assertThat(env.db.transactionDao().getAll()).hasSize(1)

            val read = messages.single { it.systemSmsId == 3L }
            assertThat(read.isRead).isTrue()

            val sent = messages.single { it.systemSmsId == 4L }
            assertThat(sent.category).isEqualTo(Category.PERSONAL)
            assertThat(sent.isRead).isTrue()
            // Sent reply shares the thread with the inbound message from the same number.
            assertThat(sent.threadId).isEqualTo(read.threadId)
        }

    @Test
    fun `resumes from the checkpoint without reprocessing completed work`() =
        runBlocking {
            addMixedRows(1L..10L)
            val env = Env("resume")
            // Pretend rows 1..5 were already processed by an interrupted run.
            env.checkpoints.set(SyncCheckpointStore.Checkpoint(lastSystemSmsId = 5L, processedCount = 5))

            val progress = mutableListOf<Pair<Int, Int>>()
            val inserted = env.importer.importAll { imported, total -> progress += imported to total }.inserted

            assertThat(inserted).isEqualTo(5)
            assertThat(
                env.db
                    .messageDao()
                    .getAll()
                    .map { it.systemSmsId },
            ).containsExactly(6L, 7L, 8L, 9L, 10L)
            // Progress accounts for the already-completed portion.
            assertThat(progress.first()).isEqualTo(5 to 10)
            assertThat(progress.last()).isEqualTo(10 to 10)
        }

    @Test
    fun `a later run picks up only new messages`() =
        runBlocking {
            addMixedRows(1L..10L)
            val env = Env("incremental")
            assertThat(env.importer.importAll().inserted).isEqualTo(10)

            addMixedRows(11L..15L)
            assertThat(env.importer.importAll().inserted).isEqualTo(5)
            assertThat(env.db.messageDao().getAll()).hasSize(15)
        }

    @Test
    fun `catch-up after a completed run classifies new rows and reports them fresh`() =
        runBlocking {
            addMixedRows(1L..8L)
            val env = Env("catchup")
            env.importer.importAll()
            val transactionsBefore =
                env.db
                    .transactionDao()
                    .getAll()
                    .size

            // Messages that landed in the provider while another app was the
            // default: a catch-up re-run must put them through the FULL
            // pipeline (categorization + extraction) and report them fresh -
            // they are newer than the watermark, so the user has never been
            // notified about them (the worker notifies via CatchUpNotifier).
            addInbox(9, "AX-BOOKMY", otpBody(9))
            addInbox(10, "VM-HDFCBK", txnBody(10))
            val result = env.importer.importAll()
            assertThat(result.inserted).isEqualTo(2)
            assertThat(result.freshCount).isEqualTo(2)
            assertThat(result.freshMessages.map { it.systemSmsId }).containsExactly(9L, 10L)

            val messages = env.db.messageDao().getAll()
            val otp = messages.single { it.systemSmsId == 9L }
            assertThat(otp.category).isEqualTo(Category.OTP)
            assertThat(otp.extractedOtp).isEqualTo("1009")
            val txn = messages.single { it.systemSmsId == 10L }
            assertThat(txn.category).isEqualTo(Category.IMPORTANT)
            assertThat(env.db.transactionDao().getAll()).hasSize(transactionsBefore + 1)

            // Bulk persistence itself never posts per message - notification
            // policy lives in the worker's CatchUpNotifier, driven by this
            // result. The shade stays empty here.
            val notificationManager = context.getSystemService(NotificationManager::class.java)
            assertThat(shadowOf(notificationManager).size()).isEqualTo(0)
        }

    @Test
    fun `fresh-install initial import reports nothing fresh`() =
        runBlocking {
            // Empty database → null watermark → ALL history is old: the
            // onboarding import must stay silent end-to-end.
            addMixedRows(1L..10L)
            val env = Env("freshinstall")
            val result = env.importer.importAll()
            assertThat(result.inserted).isEqualTo(10)
            assertThat(result.freshCount).isEqualTo(0)
            assertThat(result.freshMessages).isEmpty()
        }

    @Test
    fun `catch-up of rows older than the watermark reports nothing fresh`() =
        runBlocking {
            addMixedRows(1L..4L)
            val env = Env("oldrows")
            env.importer.importAll()

            // A higher provider _id with an OLDER date (backfill / restored
            // history) is not something the user missed being notified about.
            FakeSmsProvider.rows +=
                FakeSmsProvider.Row(5, "9876543210", "old backfilled text", date = 1_600_000_000_000, type = 1, read = 0)
            val result = env.importer.importAll()
            assertThat(result.inserted).isEqualTo(1)
            assertThat(result.freshCount).isEqualTo(0)
        }

    @Test
    fun `fresh sample is capped while the count keeps the truth`() =
        runBlocking {
            addMixedRows(1L..4L)
            val env = Env("capped")
            env.importer.importAll()

            // Seven new incoming rows (newer than everything stored) plus one
            // outgoing: outgoing rows are never "fresh", and the sample stops
            // at the per-message notification cap.
            for (id in 5L..11L) addInbox(id, "98765000$id", "missed message $id")
            addSent(12, "9876543210", "my own reply")
            val result = env.importer.importAll()
            assertThat(result.inserted).isEqualTo(8)
            assertThat(result.freshCount).isEqualTo(7)
            assertThat(result.freshMessages).hasSize(5)
            assertThat(result.freshMessages.none { it.isOutgoing }).isTrue()
        }

    @Test
    fun `reprocessing after checkpoint loss cannot duplicate messages or finance rows`() =
        runBlocking {
            addMixedRows(1L..12L)
            val env = Env("idempotent")
            env.importer.importAll()

            val messagesAfterFirst = env.db.messageDao().getAll()
            val transactionsAfterFirst = env.db.transactionDao().getAll()
            assertThat(messagesAfterFirst).hasSize(12)
            assertThat(transactionsAfterFirst).isNotEmpty()

            // Losing the checkpoint forces a full re-scan; the unique
            // systemSmsId index must keep it a no-op.
            env.checkpoints.clear()
            val insertedOnRerun = env.importer.importAll().inserted

            assertThat(insertedOnRerun).isEqualTo(0)
            assertThat(env.db.messageDao().getAll()).isEqualTo(messagesAfterFirst)
            assertThat(env.db.transactionDao().getAll()).isEqualTo(transactionsAfterFirst)
        }

    @Test
    fun `processes in pages and checkpoints once per page`() =
        runBlocking {
            addMixedRows(1L..1200L)
            val env = Env("paging")

            val progress = mutableListOf<Pair<Int, Int>>()
            val inserted = env.importer.importAll { imported, total -> progress += imported to total }.inserted

            assertThat(inserted).isEqualTo(1200)
            // Initial callback plus one per 500-row page - not one per message.
            assertThat(progress).containsExactly(0 to 1200, 500 to 1200, 1000 to 1200, 1200 to 1200).inOrder()
            assertThat(env.checkpoints.get()).isEqualTo(SyncCheckpointStore.Checkpoint(1200L, 1200))
        }

    @Test
    fun `parallel classification is deterministic across runs`() =
        runBlocking {
            addMixedRows(1L..600L)
            val first = Env("determinism-a")
            val second = Env("determinism-b")

            first.importer.importAll()
            // The second env has its own db and checkpoint store.
            second.importer.importAll()

            suspend fun projection(db: ClearSmsDatabase) =
                db.messageDao().getAll().sortedBy { it.systemSmsId }.map {
                    listOf(
                        it.systemSmsId,
                        it.threadId,
                        it.sender,
                        it.body,
                        it.timestamp,
                        it.isRead,
                        it.category,
                        it.subCategory,
                        it.extractedOtp,
                        it.extractedDataJson,
                    )
                }

            assertThat(projection(first.db)).isEqualTo(projection(second.db))
            assertThat(
                first.db
                    .transactionDao()
                    .getAll()
                    .map { it.copy(id = 0, rawSmsId = 0) },
            ).isEqualTo(
                second.db
                    .transactionDao()
                    .getAll()
                    .map { it.copy(id = 0, rawSmsId = 0) },
            )
        }

    /**
     * Minimal `content://sms` stand-in honoring the importer's exact query
     * shape: `_id > ?` + type filter, `_id ASC LIMIT n` ordering.
     */
    class FakeSmsProvider : ContentProvider() {
        data class Row(
            val id: Long,
            val address: String?,
            val body: String?,
            val date: Long,
            val type: Int,
            val read: Int,
        )

        override fun onCreate(): Boolean = true

        override fun query(
            uri: Uri,
            projection: Array<out String>?,
            selection: String?,
            selectionArgs: Array<out String>?,
            sortOrder: String?,
        ): Cursor {
            val afterId = selectionArgs?.firstOrNull()?.toLongOrNull() ?: Long.MIN_VALUE
            val limit =
                sortOrder
                    ?.substringAfterLast("LIMIT ", missingDelimiterValue = "")
                    ?.trim()
                    ?.toIntOrNull() ?: Int.MAX_VALUE
            val cursor =
                MatrixCursor(
                    arrayOf(
                        Telephony.Sms._ID,
                        Telephony.Sms.ADDRESS,
                        Telephony.Sms.BODY,
                        Telephony.Sms.DATE,
                        Telephony.Sms.TYPE,
                        Telephony.Sms.READ,
                    ),
                )
            rows
                .asSequence()
                .filter { it.id > afterId && (it.type == 1 || it.type == 2) }
                .sortedBy { it.id }
                .take(limit)
                .forEach { cursor.addRow(arrayOf(it.id, it.address, it.body, it.date, it.type, it.read)) }
            return cursor
        }

        override fun getType(uri: Uri): String? = null

        override fun insert(
            uri: Uri,
            values: ContentValues?,
        ): Uri? = null

        override fun delete(
            uri: Uri,
            selection: String?,
            selectionArgs: Array<out String>?,
        ): Int = 0

        override fun update(
            uri: Uri,
            values: ContentValues?,
            selection: String?,
            selectionArgs: Array<out String>?,
        ): Int = 0

        companion object {
            val rows = mutableListOf<Row>()
        }
    }
}
