package app.clearsms.data.db

import androidx.paging.PagingSource
import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import app.clearsms.domain.model.Category
import kotlinx.coroutines.flow.Flow

/**
 * Count of unread CONVERSATIONS for one category (badge counter). A
 * conversation counts as unread when its representative (latest) message is
 * unread - the same basis the inbox unread filter uses - so the badge always
 * equals the number of rows shown when that filter is applied.
 */
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
            WHERE deletedAt IS NULL
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

    @Query("SELECT * FROM messages WHERE threadId = :threadId AND deletedAt IS NULL ORDER BY timestamp ASC")
    fun observeThread(threadId: Long): Flow<List<MessageEntity>>

    /**
     * Paged variant of [observeInbox]: same latest-per-thread rows, loaded
     * incrementally, each joined with its thread's draft and pin. Draft
     * presence never changes the ordering or the unread state - it only
     * decorates the preview. Pinned threads sort ABOVE everything else
     * (normal recency order within each group), and the category / unread
     * filters still apply to them - a pinned promotional thread only shows
     * under pills that would show it anyway. Search deliberately ignores
     * pins (see [pagingSearch]).
     */
    @Query(
        """
        SELECT m.*, d.text AS draftText, p.pinnedAt AS pinnedAt FROM messages m
        INNER JOIN (
            SELECT threadId, MAX(timestamp) AS maxTs, MAX(id) AS maxId
            FROM messages
            WHERE deletedAt IS NULL
            GROUP BY threadId
        ) latest ON m.threadId = latest.threadId AND m.id = latest.maxId
        LEFT JOIN drafts d ON d.threadId = m.threadId
        LEFT JOIN thread_pins p ON p.normalizedSender = m.normalizedSender
        WHERE m.isArchived = 0
          AND (:category IS NULL OR m.category = :category)
          AND (:unreadOnly = 0 OR m.isRead = 0)
        ORDER BY (p.pinnedAt IS NOT NULL) DESC, m.timestamp DESC
        """,
    )
    fun pagingInbox(
        category: Category?,
        unreadOnly: Boolean,
    ): PagingSource<Int, InboxThreadRow>

    /** Distinct normalized senders of the given threads (pin toggling). */
    @Query("SELECT DISTINCT normalizedSender FROM messages WHERE threadId IN (:threadIds)")
    suspend fun normalizedSendersForThreads(threadIds: List<Long>): List<String>

    /**
     * Paged conversation, NEWEST first (rendered with `reverseLayout`), so the
     * initial page is the visible bottom of the thread and history loads on
     * upward scroll. Backed by the (threadId, timestamp) index.
     */
    @Query("SELECT * FROM messages WHERE threadId = :threadId AND deletedAt IS NULL ORDER BY timestamp DESC, id DESC")
    fun pagingThread(threadId: Long): PagingSource<Int, MessageEntity>

    /** Oldest message of a thread - carries the sender for the header. */
    @Query(
        "SELECT * FROM messages WHERE threadId = :threadId AND deletedAt IS NULL ORDER BY timestamp ASC, id ASC LIMIT 1",
    )
    suspend fun firstInThread(threadId: Long): MessageEntity?

    /** Thread ids of the current inbox view, for select-all. */
    @Query(
        """
        SELECT m.threadId FROM messages m
        INNER JOIN (
            SELECT threadId, MAX(id) AS maxId FROM messages WHERE deletedAt IS NULL GROUP BY threadId
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

    @Query("SELECT id FROM messages WHERE threadId = :threadId AND deletedAt IS NULL")
    suspend fun messageIdsInThread(threadId: Long): List<Long>

    /** How many messages in the thread are newer than [messageId] (its index in DESC order). */
    @Query(
        """
        SELECT COUNT(*) FROM messages
        WHERE threadId = :threadId
          AND deletedAt IS NULL
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

    @Query("SELECT COUNT(*) FROM messages WHERE threadId IN (:threadIds) AND isRead = 0 AND deletedAt IS NULL")
    suspend fun unreadCountInThreads(threadIds: List<Long>): Int

    @Query(
        """
        SELECT m.category AS category, COUNT(*) AS count FROM messages m
        INNER JOIN (
            SELECT threadId, MAX(id) AS maxId FROM messages WHERE deletedAt IS NULL GROUP BY threadId
        ) latest ON m.threadId = latest.threadId AND m.id = latest.maxId
        WHERE m.isRead = 0 AND m.isArchived = 0
        GROUP BY m.category
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
          AND m.deletedAt IS NULL
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
        WHERE messages_fts MATCH :match AND m.deletedAt IS NULL
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
            WHERE deletedAt IS NULL
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
            SELECT threadId, MAX(id) AS maxId FROM messages WHERE deletedAt IS NULL GROUP BY threadId
        ) latest ON m.threadId = latest.threadId AND m.id = latest.maxId
        WHERE m.isArchived = 1
        ORDER BY m.timestamp DESC
        """,
    )
    suspend fun archivedThreadIds(): List<Long>

    @Query("SELECT * FROM messages WHERE category = :category AND timestamp < :cutoffMs AND deletedAt IS NULL")
    suspend fun messagesOlderThan(
        category: Category,
        cutoffMs: Long,
    ): List<MessageEntity>

    /** Count for the confirm-before-delete step of the manual OTP cleanup. */
    @Query("SELECT COUNT(*) FROM messages WHERE category = :category AND timestamp < :cutoffMs AND deletedAt IS NULL")
    suspend fun countOlderThan(
        category: Category,
        cutoffMs: Long,
    ): Int

    /** Ids behind [countOlderThan], fed into the shared bulk-delete path. */
    @Query("SELECT id FROM messages WHERE category = :category AND timestamp < :cutoffMs AND deletedAt IS NULL")
    suspend fun idsOlderThan(
        category: Category,
        cutoffMs: Long,
    ): List<Long>

    @Query("SELECT * FROM messages WHERE id = :id")
    suspend fun getById(id: Long): MessageEntity?

    @Query("SELECT * FROM messages WHERE systemSmsId = :systemSmsId LIMIT 1")
    suspend fun getBySystemId(systemSmsId: Long): MessageEntity?

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

    /**
     * LIVE messages whose sender contains [core] - the shape a rule built from
     * a message carries ("(?i)HDFCBK" matches "VM-HDFCBK"), so this is the set
     * such a rule can possibly affect. Binned rows are excluded: a blocked or
     * keyword-binned message must not gain derived finance rows from a re-sort.
     */
    @Query(
        "SELECT * FROM messages WHERE normalizedSender LIKE '%' || :core || '%' " +
            "AND deletedAt IS NULL ORDER BY id ASC",
    )
    suspend fun liveMessagesBySenderCore(core: String): List<MessageEntity>

    @Query("SELECT threadId FROM messages WHERE normalizedSender = :normalizedSender LIMIT 1")
    suspend fun threadIdFor(normalizedSender: String): Long?

    // region SIM subscription bookkeeping

    /** Records which SIM an already-ingested (incoming) message arrived on. */
    @Query("UPDATE messages SET subscriptionId = :subscriptionId WHERE id = :id")
    suspend fun setSubscriptionId(
        id: Long,
        subscriptionId: Int,
    )

    /**
     * The SIM of the newest message in the thread that recorded one - the
     * default sending SIM for a thread with no remembered per-recipient
     * choice (reply on the SIM the conversation already lives on).
     */
    @Query(
        """
        SELECT subscriptionId FROM messages
        WHERE threadId = :threadId AND subscriptionId IS NOT NULL AND deletedAt IS NULL
        ORDER BY timestamp DESC, id DESC LIMIT 1
        """,
    )
    suspend fun lastSubscriptionIdInThread(threadId: Long): Int?

    /** Every distinct SIM the stored corpus spans (drives bubble SIM tags). */
    @Query("SELECT DISTINCT subscriptionId FROM messages WHERE subscriptionId IS NOT NULL")
    suspend fun distinctSubscriptionIds(): List<Int>

    // endregion

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

    /**
     * Records the number of radio parts an outgoing message was divided into
     * and resets the delivered-part tally for a fresh dispatch.
     */
    @Query("UPDATE messages SET partCount = :partCount, deliveredParts = 0 WHERE id = :id")
    suspend fun setPartCount(
        id: Long,
        partCount: Int,
    )

    /**
     * Worst-part failure: any part's failure report marks the whole message
     * FAILED, overwriting SENT/DELIVERED (a message with a lost part was not
     * delivered). Returns the number of rows changed - 0 when the row was
     * already FAILED, so callers can notify the user exactly once even when
     * several parts of one message fail.
     */
    @Query(
        """
        UPDATE messages SET deliveryStatus = :failed
        WHERE systemSmsId = :systemSmsId
          AND (deliveryStatus IS NULL OR deliveryStatus != :failed)
        """,
    )
    suspend fun markFailedBySystemId(
        systemSmsId: Long,
        failed: DeliveryStatus = DeliveryStatus.FAILED,
    ): Int

    @Query("UPDATE messages SET deliveredParts = deliveredParts + 1 WHERE systemSmsId = :systemSmsId")
    suspend fun incrementDeliveredParts(systemSmsId: Long)

    @Query(
        """
        UPDATE messages SET deliveryStatus = :delivered
        WHERE systemSmsId = :systemSmsId
          AND deliveredParts >= partCount
          AND deliveryStatus IN (:promotable)
        """,
    )
    suspend fun promoteDeliveredIfComplete(
        systemSmsId: Long,
        delivered: DeliveryStatus = DeliveryStatus.DELIVERED,
        promotable: List<DeliveryStatus> = listOf(DeliveryStatus.SENDING, DeliveryStatus.SENT),
    ): Int

    /**
     * Records one part's carrier delivery report and applies the worst-part
     * rule: the message becomes DELIVERED only when EVERY part has reported
     * delivery AND no part has failed (FAILED is never upgraded). Returns
     * true when this report completed the delivery - the moment to mirror
     * `STATUS_COMPLETE` to the system provider row.
     */
    @Transaction
    suspend fun recordPartDelivered(systemSmsId: Long): Boolean {
        incrementDeliveredParts(systemSmsId)
        return promoteDeliveredIfComplete(systemSmsId) > 0
    }

    /** Records why the last send failed (a [app.clearsms.mms.SendFailureReason] name). */
    @Query("UPDATE messages SET sendFailureReason = :reason WHERE id = :messageId")
    suspend fun setSendFailureReason(
        messageId: Long,
        reason: String?,
    )

    /** Rewrites a failed row for re-dispatch: back to SENDING on a fresh provider row. */
    @Query(
        """
        UPDATE messages SET deliveryStatus = :status, systemSmsId = :systemSmsId, deliveredParts = 0,
            sendFailureReason = NULL
        WHERE id = :id
        """,
    )
    suspend fun resetForResend(
        id: Long,
        systemSmsId: Long?,
        status: DeliveryStatus = DeliveryStatus.SENDING,
    )

    // endregion

    // region scheduled messages

    /** Every live scheduled message (alarm re-registration after boot / time change). */
    @Query("SELECT * FROM messages WHERE deliveryStatus = :scheduled AND deletedAt IS NULL")
    suspend fun scheduledMessages(scheduled: DeliveryStatus = DeliveryStatus.SCHEDULED): List<MessageEntity>

    /**
     * Moves a scheduled message's fire time. The bubble's [MessageEntity.timestamp]
     * follows so it keeps sitting at its future position in the thread. Only
     * a still-SCHEDULED row is touched (an edit racing the alarm loses).
     */
    @Query(
        """
        UPDATE messages SET scheduledAt = :scheduledAt, timestamp = :scheduledAt
        WHERE id = :id AND deliveryStatus = :scheduled
        """,
    )
    suspend fun updateScheduledTime(
        id: Long,
        scheduledAt: Long,
        scheduled: DeliveryStatus = DeliveryStatus.SCHEDULED,
    ): Int

    /**
     * Flips a fired schedule into the normal outgoing lifecycle: SENDING,
     * stamped with the actual send time and the fresh provider row, schedule
     * cleared. Compare-and-set on SCHEDULED so a cancel or a double alarm
     * can never dispatch twice - the returned row count is the go/no-go.
     */
    @Query(
        """
        UPDATE messages SET deliveryStatus = :sending, timestamp = :timestamp,
            systemSmsId = :systemSmsId, scheduledAt = NULL, deliveredParts = 0
        WHERE id = :id AND deliveryStatus = :scheduled
        """,
    )
    suspend fun markDispatchedFromSchedule(
        id: Long,
        timestamp: Long,
        systemSmsId: Long?,
        sending: DeliveryStatus = DeliveryStatus.SENDING,
        scheduled: DeliveryStatus = DeliveryStatus.SCHEDULED,
    ): Int

    // endregion

    @Query("SELECT MAX(threadId) FROM messages")
    suspend fun maxThreadId(): Long?

    /**
     * Highest provider row id we have ever stored (satisfied by the unique
     * `systemSmsId` index); the catch-up gap probe compares it against the
     * provider's own max `_id`.
     */
    @Query("SELECT MAX(systemSmsId) FROM messages")
    suspend fun maxSystemSmsId(): Long?

    /**
     * Newest message timestamp the app has ever stored - the catch-up
     * import's notification watermark: imported rows newer than this are
     * messages the user was never notified about. NULL on a fresh install,
     * which makes the whole initial import "old" (silent).
     */
    @Query("SELECT MAX(timestamp) FROM messages")
    suspend fun maxTimestamp(): Long?

    @Query("SELECT * FROM messages WHERE systemSmsId = :systemSmsId LIMIT 1")
    suspend fun bySystemSmsId(systemSmsId: Long): MessageEntity?

    /**
     * Distinct senders whose rows carry the legacy `isBlockedSender` flag.
     * Blocking authority moved to the settings blocklist set; this scan only
     * feeds [app.clearsms.data.repository.SenderBlocker]'s app-start
     * reconcile, which folds flags written by older app versions (where the
     * inbox block action set ONLY the row flag) into the set.
     */
    @Query("SELECT DISTINCT normalizedSender FROM messages WHERE isBlockedSender = 1")
    suspend fun blockedSenderFlags(): List<String>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(message: MessageEntity): Long

    /**
     * Insert that yields `-1` instead of replacing when the unique
     * `systemSmsId` index is violated - the live-delivery path uses this so
     * losing the race against a concurrent catch-up import never REPLACEs
     * (and thereby re-ids / un-reads) the row the import already committed.
     */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIgnore(message: MessageEntity): Long

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

    @Query("UPDATE messages SET mmsStatus = :status WHERE id = :id")
    suspend fun setMmsStatus(
        id: Long,
        status: MmsStatus,
    )

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

    /** Distinct thread ids owning the given messages (notification cancellation). */
    @Query("SELECT DISTINCT threadId FROM messages WHERE id IN (:ids)")
    suspend fun threadIdsFor(ids: List<Long>): List<Long>

    /** Of [threadIds], the threads that still contain at least one unread message. */
    @Query(
        "SELECT DISTINCT threadId FROM messages WHERE threadId IN (:threadIds) AND isRead = 0 AND deletedAt IS NULL",
    )
    suspend fun threadIdsWithUnread(threadIds: List<Long>): List<Long>

    /** Unread message ids inside [threadIds] - the messages that may still own notifications. */
    @Query("SELECT id FROM messages WHERE threadId IN (:threadIds) AND isRead = 0 AND deletedAt IS NULL")
    suspend fun unreadMessageIdsInThreads(threadIds: List<Long>): List<Long>

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

    // region soft delete / recycle bin

    /** Stages live rows for deletion: hidden everywhere, provider commit deferred. */
    @Query(
        "UPDATE messages SET deletedAt = :deletedAt, providerDeletePending = 1 WHERE id IN (:ids) AND deletedAt IS NULL",
    )
    suspend fun stageDelete(
        ids: List<Long>,
        deletedAt: Long,
    )

    /** Of [ids], the rows that are still live (not soft-deleted). */
    @Query("SELECT id FROM messages WHERE id IN (:ids) AND deletedAt IS NULL")
    suspend fun liveIds(ids: List<Long>): List<Long>

    /** Live message ids of the given threads (staging input for thread deletes). */
    @Query("SELECT id FROM messages WHERE threadId IN (:threadIds) AND deletedAt IS NULL")
    suspend fun liveIdsInThreads(threadIds: List<Long>): List<Long>

    /** Reverts a staged deletion: rows become live again, nothing owed to the provider. */
    @Query("UPDATE messages SET deletedAt = NULL, providerDeletePending = 0 WHERE id IN (:ids)")
    suspend fun undoDelete(ids: List<Long>)

    /** Provider row ids still awaiting the deferred deletion commit. */
    @Query(
        "SELECT systemSmsId FROM messages WHERE id IN (:ids) AND providerDeletePending = 1 AND systemSmsId IS NOT NULL",
    )
    suspend fun pendingSystemIdsFor(ids: List<Long>): List<Long>

    /**
     * Bin commit (marks the deferred provider deletion as committed): the provider copy is GONE, so the row's `systemSmsId` must
     * go with it. Provider row ids are reusable (the telephony store's `_id`
     * is a plain INTEGER PRIMARY KEY, so SQLite hands a freed id to the next
     * insert). A row keeping a dangling id claims that id in the unique
     * index, and the next incoming message that happens to reuse it looks
     * like a duplicate and is silently dropped.
     */
    @Query("UPDATE messages SET providerDeletePending = 0, systemSmsId = NULL WHERE id IN (:ids)")
    suspend fun clearProviderPendingAndSystemId(ids: List<Long>)

    @Query("SELECT * FROM messages WHERE systemSmsId IN (:systemSmsIds)")
    suspend fun bySystemSmsIds(systemSmsIds: List<Long>): List<MessageEntity>

    /** Releases one row's claim on a reused provider id (see above). */
    @Query("UPDATE messages SET systemSmsId = NULL WHERE id = :id")
    suspend fun clearSystemSmsId(id: Long)

    @Query("UPDATE messages SET providerDeletePending = 0 WHERE id IN (:ids)")
    suspend fun clearProviderPending(ids: List<Long>)

    /** Every row whose provider deletion never committed (startup recovery). */
    @Query("SELECT id FROM messages WHERE providerDeletePending = 1")
    suspend fun pendingCommitIds(): List<Long>

    /** Recycle-bin contents, most recently deleted first. */
    @Query("SELECT * FROM messages WHERE deletedAt IS NOT NULL ORDER BY deletedAt DESC, timestamp DESC")
    fun observeBin(): Flow<List<MessageEntity>>

    @Query("SELECT id FROM messages WHERE deletedAt IS NOT NULL")
    suspend fun binIds(): List<Long>

    /** Bin rows past the retention window (auto-purge input). */
    @Query("SELECT id FROM messages WHERE deletedAt IS NOT NULL AND deletedAt < :cutoffMs")
    suspend fun expiredBinIds(cutoffMs: Long): List<Long>

    @Query("SELECT * FROM messages WHERE id IN (:ids)")
    suspend fun getByIds(ids: List<Long>): List<MessageEntity>

    /**
     * Restores one bin row to the inbox. [systemSmsId] is the freshly
     * re-inserted provider row id, or null when the re-insert failed or was
     * skipped (not the default SMS app) - the stale pre-deletion id must not
     * survive either way, because that provider row is gone.
     */
    @Query(
        "UPDATE messages SET deletedAt = NULL, providerDeletePending = 0, systemSmsId = :systemSmsId WHERE id = :id",
    )
    suspend fun restoreRow(
        id: Long,
        systemSmsId: Long?,
    )

    // endregion

    @Query("DELETE FROM messages WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("DELETE FROM messages WHERE id IN (:ids)")
    suspend fun deleteByIds(ids: List<Long>)

    @Query("DELETE FROM messages WHERE threadId IN (:threadIds)")
    suspend fun deleteByThreadIds(threadIds: List<Long>)

    @Query("DELETE FROM messages")
    suspend fun deleteAll()
}
