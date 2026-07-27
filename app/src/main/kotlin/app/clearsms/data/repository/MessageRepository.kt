package app.clearsms.data.repository

import androidx.paging.PagingSource
import app.clearsms.data.db.CategoryUnreadCount
import app.clearsms.data.db.MessageEntity
import app.clearsms.domain.model.Category
import kotlinx.coroutines.flow.Flow

/** Access to messages: inbox observation, search, mutations and ingestion. */
interface MessageRepository {
    /** Latest message per thread, optionally filtered by category / unread state. */
    fun observeInbox(
        category: Category?,
        unreadOnly: Boolean,
    ): Flow<List<MessageEntity>>

    fun observeThread(threadId: Long): Flow<List<MessageEntity>>

    /** Paged variant of [observeInbox] for incremental list loading. */
    fun pagedInbox(
        category: Category?,
        unreadOnly: Boolean,
    ): PagingSource<Int, MessageEntity>

    /** Paged thread messages, newest first (rendered reversed). */
    fun pagedThread(threadId: Long): PagingSource<Int, MessageEntity>

    /** Oldest message of the thread (stable carrier of the sender address). */
    suspend fun firstInThread(threadId: Long): MessageEntity?

    /** Thread ids matching the current inbox view, for select-all. */
    suspend fun inboxThreadIds(
        category: Category?,
        unreadOnly: Boolean,
    ): List<Long>

    suspend fun messageIdsInThread(threadId: Long): List<Long>

    /** Index of [messageId] in the newest-first thread ordering (0 when unknown). */
    suspend fun positionInThread(
        threadId: Long,
        messageId: Long,
    ): Int

    /** Bodies of the given messages in chronological order (bulk copy). */
    suspend fun bodiesInOrder(ids: List<Long>): List<String>

    fun observeUnreadCounts(): Flow<List<CategoryUnreadCount>>

    fun search(query: String): Flow<List<MessageEntity>>

    suspend fun markRead(
        messageId: Long,
        read: Boolean = true,
    )

    suspend fun delete(messageId: Long)

    /** Batched delete; also removes the rows from the system SMS provider. */
    suspend fun deleteMessages(ids: List<Long>)

    /** Batched whole-thread delete; also removes rows from the system provider. */
    suspend fun deleteThreads(threadIds: List<Long>)

    /**
     * Number of messages categorized [Category.OTP] older than [cutoffMs]
     * (strict `<`). Counted first so the manual "Clear older OTPs" action can
     * confirm before deleting. Only the OTP category is eligible: a message
     * that merely contains an OTP code but was categorized elsewhere is not
     * counted.
     */
    suspend fun countOtpOlderThan(cutoffMs: Long): Int

    /**
     * Deletes messages categorized [Category.OTP] older than [cutoffMs]
     * through the same batched path as [deleteMessages] (chunked single
     * transaction + system-provider sync).
     *
     * @return the number of messages deleted.
     */
    suspend fun deleteOtpOlderThan(cutoffMs: Long): Int

    suspend fun setReadForMessages(
        ids: List<Long>,
        read: Boolean,
    )

    suspend fun setReadForThreads(
        threadIds: List<Long>,
        read: Boolean,
    )

    suspend fun archiveThreads(
        threadIds: List<Long>,
        archived: Boolean = true,
    )

    suspend fun unreadCountInThreads(threadIds: List<Long>): Int

    suspend fun archive(
        messageId: Long,
        archived: Boolean = true,
    )

    /**
     * Ingests an incoming SMS: runs the categorizer and parsers, persists the
     * message plus any derived account / transaction / reminder rows, and
     * returns the enriched entity.
     */
    suspend fun insertIncoming(
        sender: String,
        body: String,
        timestampMs: Long,
    ): MessageEntity

    /**
     * Re-runs categorization and extraction over every stored message in
     * paged batches (one transaction per page, so cancellation between pages
     * leaves the database consistent).
     *
     * @param onProgress called after each committed page with
     *   (processed, total); also called once up front with (0, total).
     * @return the number of messages re-categorized.
     */
    suspend fun recategorizeAll(onProgress: suspend (processed: Int, total: Int) -> Unit = { _, _ -> }): Int

    suspend fun setBlocked(
        sender: String,
        blocked: Boolean,
    )
}
