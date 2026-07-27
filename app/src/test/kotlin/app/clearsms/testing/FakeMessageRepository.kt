package app.clearsms.testing

import androidx.paging.PagingSource
import androidx.paging.PagingState
import app.clearsms.data.db.CategoryUnreadCount
import app.clearsms.data.db.MessageEntity
import app.clearsms.data.repository.MessageRepository
import app.clearsms.domain.model.Category
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow

/** In-memory no-op [MessageRepository] for view-model tests. */
open class FakeMessageRepository : MessageRepository {
    val inbox = MutableStateFlow<List<MessageEntity>>(emptyList())
    val archived = MutableStateFlow<List<MessageEntity>>(emptyList())
    val unreadCounts = MutableStateFlow<List<CategoryUnreadCount>>(emptyList())

    /** Every pagedSearch invocation: (query, category, cutoffMs). */
    val pagedSearchCalls = mutableListOf<Triple<String, Category?, Long?>>()

    override fun observeInbox(
        category: Category?,
        unreadOnly: Boolean,
    ): Flow<List<MessageEntity>> = inbox

    override fun observeThread(threadId: Long): Flow<List<MessageEntity>> = inbox

    override fun pagedInbox(
        category: Category?,
        unreadOnly: Boolean,
    ): PagingSource<Int, MessageEntity> = ListPagingSource(inbox.value)

    override fun pagedThread(threadId: Long): PagingSource<Int, MessageEntity> = ListPagingSource(emptyList())

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

    override fun observeUnreadCounts(): Flow<List<CategoryUnreadCount>> = unreadCounts

    override fun search(query: String): Flow<List<MessageEntity>> = inbox

    override fun pagedSearch(
        query: String,
        category: Category?,
        cutoffMs: Long?,
    ): PagingSource<Int, MessageEntity> {
        pagedSearchCalls += Triple(query, category, cutoffMs)
        return ListPagingSource(emptyList())
    }

    override fun observeArchived(): Flow<List<MessageEntity>> = archived

    override suspend fun archivedThreadIds(): List<Long> = archived.value.map { it.threadId }

    override suspend fun markRead(
        messageId: Long,
        read: Boolean,
    ) = Unit

    override suspend fun delete(messageId: Long) = Unit

    override suspend fun deleteMessages(ids: List<Long>) = Unit

    override suspend fun deleteThreads(threadIds: List<Long>) = Unit

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
    ): MessageEntity = throw UnsupportedOperationException()

    override suspend fun recategorizeAll(onProgress: suspend (Int, Int) -> Unit): Int = 0

    override suspend fun setBlocked(
        sender: String,
        blocked: Boolean,
    ) = Unit

    /** Single-page source over a fixed list. */
    class ListPagingSource(
        private val items: List<MessageEntity>,
    ) : PagingSource<Int, MessageEntity>() {
        override fun getRefreshKey(state: PagingState<Int, MessageEntity>): Int? = null

        override suspend fun load(params: LoadParams<Int>): LoadResult<Int, MessageEntity> =
            LoadResult.Page(data = items, prevKey = null, nextKey = null)
    }
}
