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
 * checkpointed history import, and the once-per-process cold-start probe
 * enqueues it only when the provider holds rows newer than anything stored
 * locally (messages that arrived while another app was default).
 */
@RunWith(RobolectricTestRunner::class)
class CatchUpSyncSchedulerTest {
    private lateinit var context: Context
    private lateinit var db: ClearSmsDatabase
    private lateinit var workManager: WorkManager
    private lateinit var scheduler: CatchUpSyncScheduler

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        WorkManagerTestInitHelper.initializeTestWorkManager(context)
        workManager = WorkManager.getInstance(context)
        FakeMaxIdProvider.maxId = null
        FakeMaxIdProvider.queries = 0
        Robolectric.setupContentProvider(FakeMaxIdProvider::class.java, "sms")
        db =
            Room
                .inMemoryDatabaseBuilder(context, ClearSmsDatabase::class.java)
                .allowMainThreadQueries()
                .build()
        scheduler = CatchUpSyncScheduler(context, db.messageDao(), workManager, Dispatchers.IO)
    }

    @After
    fun tearDown() {
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
    fun `probe with an empty local database but provider rows enqueues`() =
        runBlocking {
            FakeMaxIdProvider.maxId = 3L
            scheduler.onRoleChecked(held = true, regained = false)
            assertThat(enqueued()).isEqualTo(1)
        }

    @Test
    fun `probe runs once per process - later checks do not re-query the provider`() =
        runBlocking {
            storeLocalMessage(systemSmsId = 10L)
            FakeMaxIdProvider.maxId = 10L
            scheduler.onRoleChecked(held = true, regained = false)
            assertThat(FakeMaxIdProvider.queries).isEqualTo(1)
            scheduler.onRoleChecked(held = true, regained = false)
            assertThat(FakeMaxIdProvider.queries).isEqualTo(1)
            assertThat(enqueued()).isEqualTo(0)
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
