package app.clearsms.data.db

import androidx.paging.PagingSource
import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import app.clearsms.domain.model.Category
import kotlinx.coroutines.flow.Flow

/** Unread message count for one category (projection for badge counters). */
data class CategoryUnreadCount(
    val category: Category,
    val count: Int,
)

@Dao
interface MessageDao {
    /**
     * Latest message per thread for the inbox list, newest first.
     * Optional [category] filter and [unreadOnly] flag; archived threads are excluded.
     */
    @Query(
        """
        SELECT m.* FROM messages m
        INNER JOIN (
            SELECT threadId, MAX(timestamp) AS maxTs, MAX(id) AS maxId
            FROM messages
            GROUP BY threadId
        ) latest ON m.threadId = latest.threadId AND m.id = latest.maxId
        WHERE m.isArchived = 0
          AND (:category IS NULL OR m.category = :category)
          AND (:unreadOnly = 0 OR m.isRead = 0)
        ORDER BY m.timestamp DESC
        """,
    )
    fun observeInbox(
        category: Category?,
        unreadOnly: Boolean,
    ): Flow<List<MessageEntity>>

    @Query("SELECT * FROM messages WHERE threadId = :threadId ORDER BY timestamp ASC")
    fun observeThread(threadId: Long): Flow<List<MessageEntity>>

    /** Paged variant of [observeInbox]: same latest-per-thread rows, loaded incrementally. */
    @Query(
        """
        SELECT m.* FROM messages m
        INNER JOIN (
            SELECT threadId, MAX(timestamp) AS maxTs, MAX(id) AS maxId
            FROM messages
            GROUP BY threadId
        ) latest ON m.threadId = latest.threadId AND m.id = latest.maxId
        WHERE m.isArchived = 0
          AND (:category IS NULL OR m.category = :category)
          AND (:unreadOnly = 0 OR m.isRead = 0)
        ORDER BY m.timestamp DESC
        """,
    )
    fun pagingInbox(
        category: Category?,
        unreadOnly: Boolean,
    ): PagingSource<Int, MessageEntity>

    /**
     * Paged conversation, NEWEST first (rendered with `reverseLayout`), so the
     * initial page is the visible bottom of the thread and history loads on
     * upward scroll. Backed by the (threadId, timestamp) index.
     */
    @Query("SELECT * FROM messages WHERE threadId = :threadId ORDER BY timestamp DESC, id DESC")
    fun pagingThread(threadId: Long): PagingSource<Int, MessageEntity>

    /** Oldest message of a thread — carries the sender for the header. */
    @Query("SELECT * FROM messages WHERE threadId = :threadId ORDER BY timestamp ASC, id ASC LIMIT 1")
    suspend fun firstInThread(threadId: Long): MessageEntity?

    /** Thread ids of the current inbox view, for select-all. */
    @Query(
        """
        SELECT m.threadId FROM messages m
        INNER JOIN (
            SELECT threadId, MAX(id) AS maxId FROM messages GROUP BY threadId
        ) latest ON m.threadId = latest.threadId AND m.id = latest.maxId
        WHERE m.isArchived = 0
          AND (:category IS NULL OR m.category = :category)
          AND (:unreadOnly = 0 OR m.isRead = 0)
        ORDER BY m.timestamp DESC
        """,
    )
    suspend fun inboxThreadIds(
        category: Category?,
        unreadOnly: Boolean,
    ): List<Long>

    @Query("SELECT id FROM messages WHERE threadId = :threadId")
    suspend fun messageIdsInThread(threadId: Long): List<Long>

    /** How many messages in the thread are newer than [messageId] (its index in DESC order). */
    @Query(
        """
        SELECT COUNT(*) FROM messages
        WHERE threadId = :threadId
          AND timestamp > (SELECT timestamp FROM messages WHERE id = :messageId)
        """,
    )
    suspend fun newerCountInThread(
        threadId: Long,
        messageId: Long,
    ): Int

    /** Bodies of the given messages in chronological order (bulk copy). */
    @Query("SELECT body FROM messages WHERE id IN (:ids) ORDER BY timestamp ASC, id ASC")
    suspend fun bodiesFor(ids: List<Long>): List<String>

    /** System-provider row ids behind the given messages (for provider deletion). */
    @Query("SELECT systemSmsId FROM messages WHERE id IN (:ids) AND systemSmsId IS NOT NULL")
    suspend fun systemSmsIdsFor(ids: List<Long>): List<Long>

    @Query("SELECT systemSmsId FROM messages WHERE threadId IN (:threadIds) AND systemSmsId IS NOT NULL")
    suspend fun systemSmsIdsForThreads(threadIds: List<Long>): List<Long>

    @Query("SELECT COUNT(*) FROM messages WHERE threadId IN (:threadIds) AND isRead = 0")
    suspend fun unreadCountInThreads(threadIds: List<Long>): Int

    @Query(
        """
        SELECT category, COUNT(*) AS count FROM messages
        WHERE isRead = 0 AND isArchived = 0
        GROUP BY category
        """,
    )
    fun observeUnreadCounts(): Flow<List<CategoryUnreadCount>>

    @Query(
        """
        SELECT * FROM messages
        WHERE body LIKE '%' || :query || '%' OR sender LIKE '%' || :query || '%'
        ORDER BY timestamp DESC
        """,
    )
    fun search(query: String): Flow<List<MessageEntity>>

    @Query("SELECT * FROM messages WHERE category = :category AND timestamp < :cutoffMs")
    suspend fun messagesOlderThan(
        category: Category,
        cutoffMs: Long,
    ): List<MessageEntity>

    @Query("SELECT * FROM messages WHERE id = :id")
    suspend fun getById(id: Long): MessageEntity?

    @Query("SELECT * FROM messages ORDER BY id ASC")
    suspend fun getAll(): List<MessageEntity>

    @Query("SELECT threadId FROM messages WHERE normalizedSender = :normalizedSender LIMIT 1")
    suspend fun threadIdFor(normalizedSender: String): Long?

    @Query("SELECT MAX(threadId) FROM messages")
    suspend fun maxThreadId(): Long?

    @Query("SELECT EXISTS(SELECT 1 FROM messages WHERE normalizedSender = :normalizedSender AND isBlockedSender = 1)")
    suspend fun isSenderBlocked(normalizedSender: String): Boolean

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(message: MessageEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(messages: List<MessageEntity>)

    /**
     * Batch insert that silently skips rows violating the unique
     * `systemSmsId` index, making the history import idempotent.
     *
     * @return one row id per input message, `-1` for skipped duplicates.
     */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAllIgnore(messages: List<MessageEntity>): List<Long>

    @Update
    suspend fun update(message: MessageEntity)

    @Query("UPDATE messages SET isRead = 1 WHERE threadId = :threadId")
    suspend fun markThreadRead(threadId: Long)

    @Query("UPDATE messages SET isRead = :read WHERE id = :id")
    suspend fun markRead(
        id: Long,
        read: Boolean,
    )

    @Query("UPDATE messages SET isRead = :read WHERE id IN (:ids)")
    suspend fun setReadForIds(
        ids: List<Long>,
        read: Boolean,
    )

    @Query("UPDATE messages SET isRead = :read WHERE threadId IN (:threadIds)")
    suspend fun setReadForThreads(
        threadIds: List<Long>,
        read: Boolean,
    )

    @Query("UPDATE messages SET isArchived = :archived WHERE threadId IN (:threadIds)")
    suspend fun setArchivedForThreads(
        threadIds: List<Long>,
        archived: Boolean,
    )

    @Query("UPDATE messages SET isArchived = :archived WHERE id = :id")
    suspend fun setArchived(
        id: Long,
        archived: Boolean,
    )

    @Query("UPDATE messages SET isBlockedSender = :blocked WHERE normalizedSender = :normalizedSender")
    suspend fun setBlockedSender(
        normalizedSender: String,
        blocked: Boolean,
    )

    @Query("DELETE FROM messages WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("DELETE FROM messages WHERE id IN (:ids)")
    suspend fun deleteByIds(ids: List<Long>)

    @Query("DELETE FROM messages WHERE threadId IN (:threadIds)")
    suspend fun deleteByThreadIds(threadIds: List<Long>)

    @Query("DELETE FROM messages")
    suspend fun deleteAll()
}
