package app.clearsms.work

import android.content.Context
import android.os.Looper
import androidx.paging.PagingSource
import androidx.test.core.app.ApplicationProvider
import androidx.work.Configuration
import androidx.work.ListenableWorker
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import androidx.work.testing.WorkManagerTestInitHelper
import app.clearsms.data.db.CategoryUnreadCount
import app.clearsms.data.db.MessageEntity
import app.clearsms.data.repository.BinRestoreResult
import app.clearsms.data.repository.MessageRepository
import app.clearsms.domain.model.Category
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

/**
 * Durability contract of the manual re-sort worker: unique work with KEEP
 * (re-triggering never restarts a running sort), user cancellation, and the
 * re-categorized count reported through the output data.
 */
@RunWith(RobolectricTestRunner::class)
class RecategorizeWorkerTest {
    private lateinit var context: Context
    private lateinit var workManager: WorkManager

    /** Gate released by the test; until then the fake re-sort stays RUNNING. */
    private val gate = CompletableDeferred<Unit>()

    /** Fake repository: only [recategorizeAll] matters to the worker. */
    private val fakeRepository =
        object : MessageRepository {
            override suspend fun recategorizeAll(onProgress: suspend (Int, Int) -> Unit): Int {
                onProgress(0, 7)
                gate.await()
                onProgress(7, 7)
                return 7
            }

            override fun observeInbox(
                category: Category?,
                unreadOnly: Boolean,
            ): Flow<List<MessageEntity>> = emptyFlow()

            override fun observeThread(threadId: Long): Flow<List<MessageEntity>> = emptyFlow()

            override fun pagedInbox(
                category: Category?,
                unreadOnly: Boolean,
            ): PagingSource<Int, MessageEntity> = throw UnsupportedOperationException()

            override fun pagedThread(threadId: Long): PagingSource<Int, MessageEntity> = throw UnsupportedOperationException()

            override suspend fun firstInThread(threadId: Long): MessageEntity? = null

            override suspend fun inboxThreadIds(
                category: Category?,
                unreadOnly: Boolean,
            ): List<Long> = emptyList()

            override suspend fun messageIdsInThread(threadId: Long): List<Long> = emptyList()

            override suspend fun countOtpOlderThan(cutoffMs: Long): Int = 0

            override suspend fun deleteOtpOlderThan(cutoffMs: Long): Int = 0

            override suspend fun positionInThread(
                threadId: Long,
                messageId: Long,
            ): Int = 0

            override suspend fun bodiesInOrder(ids: List<Long>): List<String> = emptyList()

            override fun observeUnreadCounts(): Flow<List<CategoryUnreadCount>> = emptyFlow()

            override fun search(query: String): Flow<List<MessageEntity>> = emptyFlow()

            override fun pagedSearch(
                query: String,
                category: Category?,
                cutoffMs: Long?,
            ): PagingSource<Int, MessageEntity> = throw UnsupportedOperationException()

            override fun observeArchived(): Flow<List<MessageEntity>> = emptyFlow()

            override suspend fun archivedThreadIds(): List<Long> = emptyList()

            override suspend fun markRead(
                messageId: Long,
                read: Boolean,
            ) = Unit

            override suspend fun delete(messageId: Long) = Unit

            override suspend fun deleteMessages(ids: List<Long>) = Unit

            override suspend fun deleteThreads(threadIds: List<Long>) = Unit

            override suspend fun stageDeleteMessages(ids: List<Long>): List<Long> = ids

            override suspend fun stageDeleteThreads(threadIds: List<Long>): List<Long> = threadIds

            override suspend fun undoStagedDelete(ids: List<Long>) = Unit

            override suspend fun commitStagedDelete(
                ids: List<Long>,
                toBin: Boolean,
            ) = Unit

            override suspend fun commitAllPendingDeletes(toBin: Boolean) = Unit

            override fun observeBin(): Flow<List<MessageEntity>> = emptyFlow()

            override suspend fun binMessageIds(): List<Long> = emptyList()

            override suspend fun restoreFromBin(ids: List<Long>): BinRestoreResult = BinRestoreResult(0, 0)

            override suspend fun deleteForever(ids: List<Long>) = Unit

            override suspend fun purgeExpiredBin(cutoffMs: Long): Int = 0

            override suspend fun setReadForMessages(
                ids: List<Long>,
                read: Boolean,
            ) = Unit

            override suspend fun setReadForThreads(
                threadIds: List<Long>,
                read: Boolean,
            ) = Unit

            override suspend fun archiveThreads(
                threadIds: List<Long>,
                archived: Boolean,
            ) = Unit

            override suspend fun unreadCountInThreads(threadIds: List<Long>): Int = 0

            override suspend fun archive(
                messageId: Long,
                archived: Boolean,
            ) = Unit

            override suspend fun insertIncoming(
                sender: String,
                body: String,
                timestampMs: Long,
                systemSmsId: Long?,
            ): MessageEntity = throw UnsupportedOperationException()

            override suspend fun setBlocked(
                sender: String,
                blocked: Boolean,
            ) = Unit
        }

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        val config =
            Configuration
                .Builder()
                .setWorkerFactory(
                    object : WorkerFactory() {
                        override fun createWorker(
                            appContext: Context,
                            workerClassName: String,
                            workerParameters: WorkerParameters,
                        ): ListenableWorker = RecategorizeWorker(appContext, workerParameters, fakeRepository)
                    },
                ).build()
        WorkManagerTestInitHelper.initializeTestWorkManager(context, config)
        workManager = WorkManager.getInstance(context)
    }

    private fun awaitInfo(predicate: (WorkInfo) -> Boolean): WorkInfo {
        val deadline = System.currentTimeMillis() + 10_000
        while (System.currentTimeMillis() < deadline) {
            shadowOf(Looper.getMainLooper()).idle()
            val info = workManager.getWorkInfosForUniqueWork(RecategorizeWorker.WORK_NAME).get().firstOrNull()
            if (info != null && predicate(info)) return info
            Thread.sleep(20)
        }
        error("Timed out waiting for work state")
    }

    @Test
    fun `re-triggering keeps the running sort and reports the count on success`() {
        RecategorizeWorker.enqueue(workManager)
        val running = awaitInfo { it.state == WorkInfo.State.RUNNING }

        // KEEP: enqueuing again must not cancel/restart the in-flight run.
        RecategorizeWorker.enqueue(workManager)
        val infos = workManager.getWorkInfosForUniqueWork(RecategorizeWorker.WORK_NAME).get()
        assertThat(infos).hasSize(1)
        assertThat(infos.single().id).isEqualTo(running.id)

        gate.complete(Unit)
        val done = awaitInfo { it.state.isFinished }
        assertThat(done.state).isEqualTo(WorkInfo.State.SUCCEEDED)
        assertThat(done.outputData.getInt(RecategorizeWorker.OUTPUT_COUNT, -1)).isEqualTo(7)
    }

    @Test
    fun `a running sort can be cancelled`() {
        RecategorizeWorker.enqueue(workManager)
        awaitInfo { it.state == WorkInfo.State.RUNNING }

        RecategorizeWorker.cancel(workManager)

        val info = awaitInfo { it.state.isFinished }
        assertThat(info.state).isEqualTo(WorkInfo.State.CANCELLED)
    }
}
