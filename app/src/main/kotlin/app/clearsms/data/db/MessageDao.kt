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

    /**
     * Paged full-text search over the [MessageFtsEntity] index, composed
     * with the optional category and date-range filters in SQL so filtering
     * never materializes the full match set. [match] is an FTS4 MATCH
     * expression (see `SearchQueryFormat.toFtsMatch`), not raw user input.
     */
    @Query(
        """
        SELECT m.* FROM messages m
        JOIN messages_fts ON m.id = messages_fts.rowid
        WHERE messages_fts MATCH :match
          AND (:category IS NULL OR m.category = :category)
          AND (:cutoffMs IS NULL OR m.timestamp >= :cutoffMs)
        ORDER BY m.timestamp DESC
        """,
    )
    fun pagingSearch(
        match: String,
        category: Category?,
        cutoffMs: Long?,
    ): PagingSource<Int, MessageEntity>

    /** Non-paged FTS search (repository contract / tests); newest first. */
    @Query(
        """
        SELECT m.* FROM messages m
        JOIN messages_fts ON m.id = messages_fts.rowid
        WHERE messages_fts MATCH :match
        ORDER BY m.timestamp DESC
        """,
    )
    fun search(match: String): Flow<List<MessageEntity>>

    /** Latest message per archived thread, newest first. */
    @Query(
        """
        SELECT m.* FROM messages m
        INNER JOIN (
            SELECT threadId, MAX(id) AS maxId
            FROM messages
            GROUP BY threadId
        ) latest ON m.threadId = latest.threadId AND m.id = latest.maxId
        WHERE m.isArchived = 1
        ORDER BY m.timestamp DESC
        """,
    )
    fun observeArchived(): Flow<List<MessageEntity>>

    /** Thread ids of the archived view, for select-all. */
    @Query(
        """
        SELECT m.threadId FROM messages m
        INNER JOIN (
            SELECT threadId, MAX(id) AS maxId FROM messages GROUP BY threadId
        ) latest ON m.threadId = latest.threadId AND m.id = latest.maxId
        WHERE m.isArchived = 1
        ORDER BY m.timestamp DESC
        """,
    )
    suspend fun archivedThreadIds(): List<Long>

    @Query("SELECT * FROM messages WHERE category = :category AND timestamp < :cutoffMs")
    suspend fun messagesOlderThan(
        category: Category,
        cutoffMs: Long,
    ): List<MessageEntity>

    /** Count for the confirm-before-delete step of the manual OTP cleanup. */
    @Query("SELECT COUNT(*) FROM messages WHERE category = :category AND timestamp < :cutoffMs")
    suspend fun countOlderThan(
        category: Category,
        cutoffMs: Long,
    ): Int

    /** Ids behind [countOlderThan], fed into the shared bulk-delete path. */
    @Query("SELECT id FROM messages WHERE category = :category AND timestamp < :cutoffMs")
    suspend fun idsOlderThan(
        category: Category,
        cutoffMs: Long,
    ): List<Long>

    @Query("SELECT * FROM messages WHERE id = :id")
    suspend fun getById(id: Long): MessageEntity?

    @Query("SELECT * FROM messages ORDER BY id ASC")
    suspend fun getAll(): List<MessageEntity>

    /** Total row count (denominator for re-categorization progress). */
    @Query("SELECT COUNT(*) FROM messages")
    suspend fun count(): Int

    /**
     * Keyset page for full-table scans (re-categorization): rows after
     * [afterId] in id order. Stable under in-place updates, unlike OFFSET.
     */
    @Query("SELECT * FROM messages WHERE id > :afterId ORDER BY id ASC LIMIT :limit")
    suspend fun pageAfter(
        afterId: Long,
        limit: Int,
    ): List<MessageEntity>

    @Query("SELECT threadId FROM messages WHERE normalizedSender = :normalizedSender LIMIT 1")
    suspend fun threadIdFor(normalizedSender: String): Long?

    // region outgoing message status

    @Query("UPDATE messages SET deliveryStatus = :status WHERE id = :id")
    suspend fun setDeliveryStatus(
        id: Long,
        status: DeliveryStatus,
    )

    @Query("UPDATE messages SET deliveryStatus = :status WHERE systemSmsId = :systemSmsId")
    suspend fun setDeliveryStatusBySystemId(
        systemSmsId: Long,
        status: DeliveryStatus,
    )

    /**
     * Compare-and-set status transition: applies [new] only while the row is
     * still at [expected]. Guards the ordering SENDING → SENT → DELIVERED so
     * a late sent-report can never downgrade a DELIVERED message.
     */
    @Query(
        """
        UPDATE messages SET deliveryStatus = :newStatus
        WHERE systemSmsId = :systemSmsId AND deliveryStatus = :expected
        """,
    )
    suspend fun promoteDeliveryStatusBySystemId(
        systemSmsId: Long,
        expected: DeliveryStatus,
        newStatus: DeliveryStatus,
    )

    /** Same compare-and-set as above, keyed by our own row id. */
    @Query("UPDATE messages SET deliveryStatus = :newStatus WHERE id = :id AND deliveryStatus = :expected")
    suspend fun promoteDeliveryStatus(
        id: Long,
        expected: DeliveryStatus,
        newStatus: DeliveryStatus,
    )

    /** Live status of one outgoing message (drives the send-outcome snackbar). */
    @Query("SELECT deliveryStatus FROM messages WHERE id = :id")
    fun observeDeliveryStatus(id: Long): Flow<DeliveryStatus?>

    /** Rewrites a failed row for re-dispatch: back to SENDING on a fresh provider row. */
    @Query("UPDATE messages SET deliveryStatus = :status, systemSmsId = :systemSmsId WHERE id = :id")
    suspend fun resetForResend(
        id: Long,
        systemSmsId: Long?,
        status: DeliveryStatus = DeliveryStatus.SENDING,
    )

    // endregion

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
