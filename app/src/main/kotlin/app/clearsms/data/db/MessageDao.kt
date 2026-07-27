package app.clearsms.data.db

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

    @Query("DELETE FROM messages")
    suspend fun deleteAll()
}
