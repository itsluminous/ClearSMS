package app.clearsms.data.repository

import androidx.paging.PagingSource
import app.clearsms.data.db.CategoryUnreadCount
import app.clearsms.data.db.InboxThreadRow
import app.clearsms.data.db.MessageEntity
import app.clearsms.domain.model.Category
import kotlinx.coroutines.flow.Flow

/** Metadata of one stored MMS attachment file, pending its Room row. */
data class MmsAttachmentDraft(
    val mimeType: String,
    val fileName: String,
    val sizeBytes: Long,
)

/** Outcome of a recycle-bin restore. */
data class BinRestoreResult(
    /** Rows made live again in the app database. */
    val restored: Int,
    /** Of [restored], how many were re-inserted into the system provider. */
    val reinserted: Int,
) {
    /** True when every restored row is back in the system provider too. */
    val fullyReinserted: Boolean get() = restored == reinserted
}

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
    ): PagingSource<Int, InboxThreadRow>

    // region drafts

    /** The thread's saved draft text, or null when it has none. */
    suspend fun draftFor(threadId: Long): String?

    /**
     * Saves [text] as the thread's draft; blank text deletes the draft
     * (sending, scheduling or clearing the compose field all clear it).
     */
    suspend fun saveDraft(
        threadId: Long,
        text: String,
    )

    // endregion

    // region pinned conversations

    /**
     * Pins or unpins the given threads. Pins are stored per normalized
     * sender (threads are derived, sender identity is stable), so a pin
     * survives message backup/restore and thread-id reassignment.
     */
    suspend fun setPinned(
        threadIds: List<Long>,
        pinned: Boolean,
    )

    /** How many of the given threads are currently pinned. */
    suspend fun pinnedCountInThreads(threadIds: List<Long>): Int

    // endregion

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

    /**
     * The SIM of the newest message in the thread that recorded one (the
     * thread's natural sending SIM when the user never chose one). Default
     * null: implementations without SIM bookkeeping simply have no history.
     */
    suspend fun lastSubscriptionIdInThread(threadId: Long): Int? = null

    /** Every distinct SIM the stored corpus spans (drives bubble SIM tags). */
    suspend fun distinctSubscriptionIds(): List<Int> = emptyList()

    fun observeUnreadCounts(): Flow<List<CategoryUnreadCount>>

    /** Full-text search (token-prefix match); empty flow for unsearchable input. */
    fun search(query: String): Flow<List<MessageEntity>>

    /**
     * Paged full-text search with the category / date filters composed into
     * the SQL. [query] is raw user input - sanitized here; an unsearchable
     * query yields an empty page source.
     */
    fun pagedSearch(
        query: String,
        category: Category?,
        cutoffMs: Long?,
    ): PagingSource<Int, MessageEntity>

    /** Latest message per archived thread, newest first. */
    fun observeArchived(): Flow<List<MessageEntity>>

    /** Thread ids of the archived view, for select-all. */
    suspend fun archivedThreadIds(): List<Long>

    suspend fun markRead(
        messageId: Long,
        read: Boolean = true,
    )

    suspend fun delete(messageId: Long)

    /** Batched delete; also removes the rows from the system SMS provider. */
    suspend fun deleteMessages(ids: List<Long>)

    /** Batched whole-thread delete; also removes rows from the system provider. */
    suspend fun deleteThreads(threadIds: List<Long>)

    // region undoable delete / recycle bin

    /**
     * Soft-deletes [ids]: the rows disappear from every read path
     * immediately and their notifications are cancelled, but the system-
     * provider deletion is DEFERRED until [commitStagedDelete] - so
     * [undoStagedDelete] can restore them without re-inserting provider
     * rows.
     *
     * @return the ids actually staged (already-deleted rows are skipped).
     */
    suspend fun stageDeleteMessages(ids: List<Long>): List<Long>

    /** Whole-thread variant of [stageDeleteMessages]; returns staged message ids. */
    suspend fun stageDeleteThreads(threadIds: List<Long>): List<Long>

    /** Reverts a staged deletion: rows are live again, provider untouched. */
    suspend fun undoStagedDelete(ids: List<Long>)

    /**
     * Commits a staged deletion: the deferred provider rows are deleted,
     * then the app rows are hard-deleted ([toBin] false) or left resting in
     * the recycle bin ([toBin] true).
     */
    suspend fun commitStagedDelete(
        ids: List<Long>,
        toBin: Boolean,
    )

    /** Commits every staged deletion that never committed (startup recovery). */
    suspend fun commitAllPendingDeletes(toBin: Boolean)

    /** Recycle-bin contents, most recently deleted first. */
    fun observeBin(): Flow<List<MessageEntity>>

    suspend fun binMessageIds(): List<Long>

    /**
     * Restores bin rows to the inbox and re-inserts them into the system
     * provider when possible (default SMS app only).
     */
    suspend fun restoreFromBin(ids: List<Long>): BinRestoreResult

    /** Hard-deletes bin rows - exactly what [deleteMessages] does today. */
    suspend fun deleteForever(ids: List<Long>)

    /**
     * Hard-deletes bin rows older than [cutoffMs] (the 30-day retention
     * sweep). @return the number of purged messages.
     */
    suspend fun purgeExpiredBin(cutoffMs: Long): Int

    // endregion

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
        /**
         * The system-provider row id (`content://sms/<id>`) the default-app
         * write produced, when known. Linking it lets delete commits remove
         * the provider row too, instead of silently no-oping.
         */
        systemSmsId: Long? = null,
    ): MessageEntity

    /**
     * Outcome of [ingestIncoming]: the stored entity plus whether the row
     * already existed (a concurrent catch-up import won the `systemSmsId`
     * race). Exactly one of the two paths may notify; [duplicate] is the
     * receiver's cue that the import path owns this message's notification.
     */
    data class IncomingIngest(
        val entity: MessageEntity,
        val duplicate: Boolean,
    )

    /**
     * Like [insertIncoming], but reports whether the message row already
     * existed under the same `systemSmsId` (inserted first by a racing
     * catch-up import). Implementations that cannot race default to
     * `duplicate = false`.
     */
    suspend fun ingestIncoming(
        sender: String,
        body: String,
        timestampMs: Long,
        systemSmsId: Long? = null,
    ): IncomingIngest = IncomingIngest(insertIncoming(sender, body, timestampMs, systemSmsId), duplicate = false)

    // region MMS

    /**
     * Stores the pending row for a just-announced MMS (m-notification-ind):
     * an empty-bodied message in [app.clearsms.data.db.MmsStatus.PENDING]
     * that the conversation renders as "downloading" until
     * [completeMmsDownload] or [markMmsFailed] resolves it.
     */
    suspend fun insertMmsNotification(
        sender: String,
        timestampMs: Long,
        transactionId: String,
        contentLocation: String,
    ): MessageEntity = insertIncoming(sender, "", timestampMs)

    /**
     * Finalizes a downloaded MMS: the text part becomes the body (run
     * through the normal categorization/extraction pipeline), attachment
     * metadata rows are written, the row is re-attributed to [sender] when
     * the notification lacked one, and group recipients are recorded on the
     * row. Returns the updated entity for notification routing, or null
     * when the row no longer exists.
     */
    suspend fun completeMmsDownload(
        messageId: Long,
        sender: String?,
        body: String,
        recipients: List<String>,
        attachments: List<MmsAttachmentDraft>,
    ): MessageEntity? = null

    /** Marks a pending MMS row [app.clearsms.data.db.MmsStatus.FAILED] (retry exhausted). */
    suspend fun markMmsFailed(messageId: Long) {}

    /**
     * Flips a FAILED MMS row back to PENDING for a user-initiated retry;
     * returns the row (carrying its stored content location) or null when
     * it is gone or has no location to retry.
     */
    suspend fun markMmsPendingForRetry(messageId: Long): MessageEntity? = null

    // endregion

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
