package app.clearsms.work

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.provider.Telephony
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.work.WorkManager
import androidx.work.testing.WorkManagerTestInitHelper
import app.clearsms.data.db.ClearSmsDatabase
import app.clearsms.data.db.MessageEntity
import app.clearsms.domain.model.Category
import app.clearsms.testing.InMemoryPreferencesDataStore
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner

/**
 * Catch-up import triggers: a regained default-SMS role always enqueues the
 * checkpointed history import, and the per-resume gap probe enqueues it
 * whenever the provider holds rows newer than anything stored locally
 * (messages that arrived while another app was default, or that a failed
 * receiver run persisted to the provider only).
 */
@RunWith(RobolectricTestRunner::class)
class CatchUpSyncSchedulerTest {
    private lateinit var context: Context
    private lateinit var db: ClearSmsDatabase
    private lateinit var workManager: WorkManager
    private lateinit var checkpoints: SyncCheckpointStore
    private lateinit var scheduler: CatchUpSyncScheduler

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        WorkManagerTestInitHelper.initializeTestWorkManager(context)
        workManager = WorkManager.getInstance(context)
        FakeMaxIdProvider.maxId = null
        FakeMaxIdProvider.queries = 0
        Robolectric.setupContentProvider(FakeMaxIdProvider::class.java, "sms")
        FakeMmsMaxIdProvider.maxId = null
        FakeMmsMaxIdProvider.lastSelection = null
        Robolectric.setupContentProvider(FakeMmsMaxIdProvider::class.java, "mms")
        db =
            Room
                .inMemoryDatabaseBuilder(context, ClearSmsDatabase::class.java)
                .allowMainThreadQueries()
                .build()
        checkpoints = SyncCheckpointStore(InMemoryPreferencesDataStore())
        // Unless a test says otherwise, the MMS walk is up to date: the
        // scope probe stays quiet and the max-id probes are what is tested.
        runBlocking { checkpoints.beginMmsWalk() }
        scheduler = CatchUpSyncScheduler(context, db.messageDao(), workManager, checkpoints, Dispatchers.IO)
    }

    @After
    fun tearDown() {
        // No worker may be left RUNNING when the test ends - an abandoned
        // worker future is finalized exceptionally by the GC long after this
        // WorkManager's database is closed and fails an unrelated later
        // runTest - see AutoResortSchedulerTest.tearDown.
        workManager.cancelAllWork().result.get()
        db.close()
    }

    private fun enqueued(): Int = workManager.getWorkInfosForUniqueWork(InitialSyncWorker.WORK_NAME).get().size

    private fun storeLocalMessage(systemSmsId: Long) =
        runBlocking {
            db.messageDao().insert(
                MessageEntity(
                    threadId = 1L,
                    sender = "9876543210",
                    normalizedSender = "9876543210",
                    body = "hello",
                    timestamp = 1L,
                    isRead = true,
                    category = Category.PERSONAL,
                    systemSmsId = systemSmsId,
                ),
            )
        }

    private fun storeLocalMms(systemMmsId: Long) =
        runBlocking {
            db.messageDao().insert(
                MessageEntity(
                    threadId = 2L,
                    sender = "9876500000",
                    normalizedSender = "9876500000",
                    body = "picture",
                    timestamp = 2L,
                    isRead = true,
                    category = Category.PERSONAL,
                    systemMmsId = systemMmsId,
                ),
            )
        }

    /** The checkpoint state of an install upgraded from 0.23.0: inbox-only walk, no scope recorded. */
    private fun inboxOnlyWalk(lastSystemMmsId: Long) =
        runBlocking {
            checkpoints.clear()
            checkpoints.setLastSystemMmsId(lastSystemMmsId)
        }

    @Test
    fun `regained role enqueues the catch-up import`() =
        runBlocking {
            scheduler.onRoleChecked(held = true, regained = true)
            assertThat(enqueued()).isEqualTo(1)
        }

    @Test
    fun `role absent never enqueues`() =
        runBlocking {
            FakeMaxIdProvider.maxId = 10L
            scheduler.onRoleChecked(held = false, regained = false)
            assertThat(enqueued()).isEqualTo(0)
        }

    @Test
    fun `cold-start probe with a provider gap enqueues the import`() =
        runBlocking {
            // Rows 6..10 landed while another app was default and we were dead.
            storeLocalMessage(systemSmsId = 5L)
            FakeMaxIdProvider.maxId = 10L
            scheduler.onRoleChecked(held = true, regained = false)
            assertThat(enqueued()).isEqualTo(1)
        }

    @Test
    fun `cold-start probe without a gap is a no-op`() =
        runBlocking {
            storeLocalMessage(systemSmsId = 10L)
            FakeMaxIdProvider.maxId = 10L
            scheduler.onRoleChecked(held = true, regained = false)
            assertThat(enqueued()).isEqualTo(0)
        }

    @Test
    fun `mms gap enqueues the import even when the sms history is complete`() =
        runBlocking {
            // The exact state of an existing install after the MMS import
            // shipped (issue #94): every SMS already stored, so the SMS probe
            // sees no gap, while the provider still holds un-imported MMS.
            storeLocalMessage(systemSmsId = 10L)
            FakeMaxIdProvider.maxId = 10L
            FakeMmsMaxIdProvider.maxId = 4L
            scheduler.onRoleChecked(held = true, regained = false)
            assertThat(enqueued()).isEqualTo(1)
        }

    @Test
    fun `no gap in either provider stays a no-op`() =
        runBlocking {
            storeLocalMessage(systemSmsId = 10L)
            storeLocalMms(systemMmsId = 4L)
            FakeMaxIdProvider.maxId = 10L
            FakeMmsMaxIdProvider.maxId = 4L
            scheduler.onRoleChecked(held = true, regained = false)
            assertThat(enqueued()).isEqualTo(0)
        }

    @Test
    fun `mms probe asks the provider for sent rows as well as received ones`() =
        runBlocking {
            // Without this, an MMS sent from another app while it held the
            // role (a newer id than anything stored) could never trigger a
            // catch-up, and the walk would only ever learn of received MMS.
            storeLocalMessage(systemSmsId = 10L)
            FakeMaxIdProvider.maxId = 10L
            scheduler.onRoleChecked(held = true, regained = false)
            val selection = FakeMmsMaxIdProvider.lastSelection
            assertThat(selection).isNotNull()
            assertThat(selection).contains(Telephony.Mms.MESSAGE_BOX_INBOX.toString())
            assertThat(selection).contains(Telephony.Mms.MESSAGE_BOX_SENT.toString())
            // Drafts and the outbox are not imported, so they must not be
            // probed either - a stranded draft would fire the probe forever.
            assertThat(selection).doesNotContain(Telephony.Mms.MESSAGE_BOX_DRAFTS.toString())
            assertThat(selection).doesNotContain(Telephony.Mms.MESSAGE_BOX_OUTBOX.toString())
        }

    @Test
    fun `an inbox-only walk is re-run even when the newest mms is already stored`() =
        runBlocking {
            // Issue #112 on an upgraded install: every SMS stored, the newest
            // MMS (a received one, id 4) stored by the 0.23.0 import, so both
            // max-id probes agree - yet the sent rows at ids 1..3 were never
            // read, because that walk looked at the inbox only. The max-id
            // probe is structurally blind to this; the recorded scope is not.
            storeLocalMessage(systemSmsId = 10L)
            storeLocalMms(systemMmsId = 4L)
            FakeMaxIdProvider.maxId = 10L
            FakeMmsMaxIdProvider.maxId = 4L
            inboxOnlyWalk(lastSystemMmsId = 4L)
            scheduler.onRoleChecked(held = true, regained = false)
            assertThat(enqueued()).isEqualTo(1)
        }

    @Test
    fun `a walk under the current scope is not re-run`() =
        runBlocking {
            // The same device after the re-walk ran: the importer recorded
            // the scope, so the probe must settle - it fires once per
            // install, never once per resume.
            storeLocalMessage(systemSmsId = 10L)
            storeLocalMms(systemMmsId = 4L)
            FakeMaxIdProvider.maxId = 10L
            FakeMmsMaxIdProvider.maxId = 4L
            checkpoints.beginMmsWalk()
            checkpoints.setLastSystemMmsId(4L)
            scheduler.onRoleChecked(held = true, regained = false)
            assertThat(enqueued()).isEqualTo(0)
        }

    @Test
    fun `probe with an empty local database but provider rows enqueues`() =
        runBlocking {
            FakeMaxIdProvider.maxId = 3L
            scheduler.onRoleChecked(held = true, regained = false)
            assertThat(enqueued()).isEqualTo(1)
        }

    @Test
    fun `probe runs on every role check so a live process still catches gaps`() =
        runBlocking {
            storeLocalMessage(systemSmsId = 10L)
            FakeMaxIdProvider.maxId = 10L
            scheduler.onRoleChecked(held = true, regained = false)
            assertThat(FakeMaxIdProvider.queries).isEqualTo(1)
            assertThat(enqueued()).isEqualTo(0)

            // A gap appearing LATER (a receiver run whose Room insert failed
            // after the provider write, or an external role switch we never
            // observed) must be caught on the next resume - a user reopening
            // a still-alive background process cannot wait for a cold start.
            FakeMaxIdProvider.maxId = 12L
            scheduler.onRoleChecked(held = true, regained = false)
            assertThat(FakeMaxIdProvider.queries).isEqualTo(2)
            assertThat(enqueued()).isEqualTo(1)
        }

    /** Fake `content://mms` serving only the probe's newest-importable-id query. */
    class FakeMmsMaxIdProvider : ContentProvider() {
        override fun onCreate(): Boolean = true

        override fun query(
            uri: Uri,
            projection: Array<out String>?,
            selection: String?,
            selectionArgs: Array<out String>?,
            sortOrder: String?,
        ): Cursor {
            lastSelection = selection
            val cursor = MatrixCursor(arrayOf(Telephony.Mms._ID))
            maxId?.let { cursor.addRow(arrayOf(it)) }
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
            var maxId: Long? = null

            /** The `msg_box` selection the probe sent, for the box-coverage test. */
            var lastSelection: String? = null
        }
    }

    /** `content://sms` stand-in answering only the scheduler's max-id probe. */
    class FakeMaxIdProvider : ContentProvider() {
        override fun onCreate(): Boolean = true

        override fun query(
            uri: Uri,
            projection: Array<out String>?,
            selection: String?,
            selectionArgs: Array<out String>?,
            sortOrder: String?,
        ): Cursor {
            queries++
            val cursor = MatrixCursor(arrayOf(Telephony.Sms._ID))
            maxId?.let { cursor.addRow(arrayOf(it)) }
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
            var maxId: Long? = null
            var queries: Int = 0
        }
    }
}
