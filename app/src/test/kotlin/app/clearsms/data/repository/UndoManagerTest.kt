package app.clearsms.data.repository

import androidx.paging.PagingSource
import app.clearsms.data.db.CategoryUnreadCount
import app.clearsms.data.db.MessageEntity
import app.clearsms.domain.model.Category
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test

/**
 * The Gmail-style undo window semantics, on virtual time: one pending
 * action at a time, timeout commits the deferred provider deletion, a new
 * destructive action commits the previous one, undo inside the window
 * reverts without any commit, and archives revert as pure flag flips.
 */
class UndoManagerTest {
    private val repository = RecordingRepository()
    private var binEnabled = false

    private fun kotlinx.coroutines.test.TestScope.manager(windowMs: Long = 5_000) =
        UndoManager(
            repository = repository,
            scope = backgroundScope,
            recycleBinEnabled = { binEnabled },
            undoWindowMs = windowMs,
        )

    @Test
    fun `timeout commits the deferred provider deletion`() =
        runTest {
            val manager = manager()
            manager.stageDeleteMessages(listOf(1L, 2L))
            assertThat(repository.commits).isEmpty()

            advanceTimeBy(5_001)
            runCurrent()

            assertThat(repository.commits).containsExactly(listOf(1L, 2L) to false)
        }

    @Test
    fun `deleteNow commits immediately honoring the bin - the notification path`() =
        runTest {
            binEnabled = true
            val manager = manager()
            manager.deleteNow(listOf(7L))
            runCurrent()
            // No undo window: committed at once, to the bin.
            assertThat(repository.commits).containsExactly(listOf(7L) to true)
        }

    @Test
    fun `deleteNow with the bin off hard-deletes immediately`() =
        runTest {
            binEnabled = false
            val manager = manager()
            manager.deleteNow(listOf(7L))
            runCurrent()
            assertThat(repository.commits).containsExactly(listOf(7L) to false)
        }

    @Test
    fun `deleteNow first commits a pending UI undo`() =
        runTest {
            val manager = manager()
            manager.stageDeleteMessages(listOf(1L))
            manager.deleteNow(listOf(2L))
            runCurrent()
            // The pending UI delete commits before the immediate one.
            assertThat(repository.commits).containsExactly(listOf(1L) to false, listOf(2L) to false).inOrder()
        }

    @Test
    fun `commit honors the recycle-bin setting read at commit time`() =
        runTest {
            binEnabled = true
            val manager = manager()
            manager.stageDeleteMessages(listOf(1L))

            advanceTimeBy(5_001)
            runCurrent()

            assertThat(repository.commits).containsExactly(listOf(1L) to true)
        }

    @Test
    fun `undo inside the window reverts and nothing ever commits`() =
        runTest {
            val manager = manager()
            manager.stageDeleteMessages(listOf(1L))

            manager.undo()
            advanceTimeBy(60_000)
            runCurrent()

            assertThat(repository.undos).containsExactly(listOf(1L))
            assertThat(repository.commits).isEmpty()
        }

    @Test
    fun `a second destructive action commits the first - one undo at a time`() =
        runTest {
            val manager = manager()
            manager.stageDeleteMessages(listOf(1L))

            manager.stageDeleteMessages(listOf(2L))
            // The first delete committed immediately (superseded)…
            assertThat(repository.commits).containsExactly(listOf(1L) to false)

            // …and undo now only reverts the second.
            manager.undo()
            advanceTimeBy(60_000)
            runCurrent()
            assertThat(repository.undos).containsExactly(listOf(2L))
            assertThat(repository.commits).containsExactly(listOf(1L) to false)
        }

    @Test
    fun `archive undo restores the archived threads`() =
        runTest {
            val manager = manager()
            manager.stageArchiveThreads(listOf(7L, 8L))
            assertThat(repository.archives).containsExactly(listOf(7L, 8L) to true)

            manager.undo()

            assertThat(repository.archives)
                .containsExactly(listOf(7L, 8L) to true, listOf(7L, 8L) to false)
                .inOrder()
        }

    @Test
    fun `single-message archive undo reverts the message flag`() =
        runTest {
            val manager = manager()
            manager.stageArchiveMessage(42L)
            assertThat(repository.messageArchives).containsExactly(42L to true)

            manager.undo()

            assertThat(repository.messageArchives).containsExactly(42L to true, 42L to false).inOrder()
        }

    @Test
    fun `archive commit after the window is a no-op - nothing deferred`() =
        runTest {
            val manager = manager()
            manager.stageArchiveThreads(listOf(7L))

            advanceTimeBy(60_000)
            runCurrent()

            assertThat(repository.commits).isEmpty()
            assertThat(repository.archives).containsExactly(listOf(7L) to true)
        }

    @Test
    fun `a delete staged over a pending archive keeps the archive applied`() =
        runTest {
            val manager = manager()
            manager.stageArchiveThreads(listOf(7L))
            manager.stageDeleteMessages(listOf(1L))

            manager.undo()

            // Undo reverts only the delete; the superseded archive stands.
            assertThat(repository.undos).containsExactly(listOf(1L))
            assertThat(repository.archives).containsExactly(listOf(7L) to true)
        }

    @Test
    fun `onAppStart commits leftovers and purges the expired bin`() =
        runTest {
            binEnabled = true
            manager().onAppStart()
            runCurrent()

            assertThat(repository.commitAllCalls).containsExactly(true)
            assertThat(repository.purgeCutoffs).hasSize(1)
        }

    /** Records the calls the manager routes; everything else is unused. */
    private class RecordingRepository : MessageRepository {
        val commits = mutableListOf<Pair<List<Long>, Boolean>>()
        val undos = mutableListOf<List<Long>>()
        val archives = mutableListOf<Pair<List<Long>, Boolean>>()
        val messageArchives = mutableListOf<Pair<Long, Boolean>>()
        val commitAllCalls = mutableListOf<Boolean>()
        val purgeCutoffs = mutableListOf<Long>()

        override suspend fun stageDeleteMessages(ids: List<Long>) = ids

        override suspend fun stageDeleteThreads(threadIds: List<Long>) = threadIds

        override suspend fun undoStagedDelete(ids: List<Long>) {
            undos += ids
        }

        override suspend fun commitStagedDelete(
            ids: List<Long>,
            toBin: Boolean,
        ) {
            commits += ids to toBin
        }

        override suspend fun commitAllPendingDeletes(toBin: Boolean) {
            commitAllCalls += toBin
        }

        override suspend fun archiveThreads(
            threadIds: List<Long>,
            archived: Boolean,
        ) {
            archives += threadIds to archived
        }

        override suspend fun archive(
            messageId: Long,
            archived: Boolean,
        ) {
            messageArchives += messageId to archived
        }

        override suspend fun purgeExpiredBin(cutoffMs: Long): Int {
            purgeCutoffs += cutoffMs
            return 0
        }

        // region unused

        override fun observeInbox(
            category: Category?,
            unreadOnly: Boolean,
        ): Flow<List<MessageEntity>> = emptyFlow()

        override fun observeThread(threadId: Long): Flow<List<MessageEntity>> = emptyFlow()

        override fun pagedInbox(
            category: Category?,
            unreadOnly: Boolean,
        ): PagingSource<Int, MessageEntity> = error("unused")

        override fun pagedThread(threadId: Long): PagingSource<Int, MessageEntity> = error("unused")

        override suspend fun firstInThread(threadId: Long): MessageEntity? = null

        override suspend fun inboxThreadIds(
            category: Category?,
            unreadOnly: Boolean,
        ): List<Long> = emptyList()

        override suspend fun messageIdsInThread(threadId: Long): List<Long> = emptyList()

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
        ): PagingSource<Int, MessageEntity> = error("unused")

        override fun observeArchived(): Flow<List<MessageEntity>> = emptyFlow()

        override suspend fun archivedThreadIds(): List<Long> = emptyList()

        override suspend fun markRead(
            messageId: Long,
            read: Boolean,
        ) = Unit

        override suspend fun delete(messageId: Long) = Unit

        override suspend fun deleteMessages(ids: List<Long>) = Unit

        override suspend fun deleteThreads(threadIds: List<Long>) = Unit

        override fun observeBin(): Flow<List<MessageEntity>> = emptyFlow()

        override suspend fun binMessageIds(): List<Long> = emptyList()

        override suspend fun restoreFromBin(ids: List<Long>): BinRestoreResult = BinRestoreResult(0, 0)

        override suspend fun deleteForever(ids: List<Long>) = Unit

        override suspend fun countOtpOlderThan(cutoffMs: Long): Int = 0

        override suspend fun deleteOtpOlderThan(cutoffMs: Long): Int = 0

        override suspend fun setReadForMessages(
            ids: List<Long>,
            read: Boolean,
        ) = Unit

        override suspend fun setReadForThreads(
            threadIds: List<Long>,
            read: Boolean,
        ) = Unit

        override suspend fun unreadCountInThreads(threadIds: List<Long>): Int = 0

        override suspend fun insertIncoming(
            sender: String,
            body: String,
            timestampMs: Long,
        ): MessageEntity = error("unused")

        override suspend fun recategorizeAll(onProgress: suspend (Int, Int) -> Unit): Int = 0

        override suspend fun setBlocked(
            sender: String,
            blocked: Boolean,
        ) = Unit

        // endregion
    }
}
