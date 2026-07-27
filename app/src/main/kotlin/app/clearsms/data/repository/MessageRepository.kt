package app.clearsms.data.repository

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

    fun observeUnreadCounts(): Flow<List<CategoryUnreadCount>>

    fun search(query: String): Flow<List<MessageEntity>>

    suspend fun markRead(
        messageId: Long,
        read: Boolean = true,
    )

    suspend fun delete(messageId: Long)

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

    /** Re-runs categorization and extraction over every stored message. */
    suspend fun recategorizeAll()

    suspend fun setBlocked(
        sender: String,
        blocked: Boolean,
    )
}
