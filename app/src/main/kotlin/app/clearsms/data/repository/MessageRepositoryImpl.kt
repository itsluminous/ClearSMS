package app.clearsms.data.repository

import androidx.paging.PagingSource
import androidx.room.withTransaction
import app.clearsms.data.db.AccountEntity
import app.clearsms.data.db.CategoryUnreadCount
import app.clearsms.data.db.ClearSmsDatabase
import app.clearsms.data.db.DeliveryStatus
import app.clearsms.data.db.DraftEntity
import app.clearsms.data.db.InboxThreadRow
import app.clearsms.data.db.MessageEntity
import app.clearsms.data.db.ReminderEntity
import app.clearsms.data.db.ThreadPinEntity
import app.clearsms.data.db.TransactionEntity
import app.clearsms.data.prefs.BlockedKeywords
import app.clearsms.data.rules.BundledRuleLoader
import app.clearsms.data.rules.RuleDefinition
import app.clearsms.data.rules.RuleSources
import app.clearsms.data.rules.toDefinition
import app.clearsms.domain.categorizer.MessageCategorizer
import app.clearsms.domain.model.AccountType
import app.clearsms.domain.model.CategorizationResult
import app.clearsms.domain.model.Category
import app.clearsms.domain.model.ExtractedValue
import app.clearsms.domain.model.MerchantCategory
import app.clearsms.domain.model.ParsedDelivery
import app.clearsms.domain.model.ParsedReminder
import app.clearsms.domain.model.ParsedTransaction
import app.clearsms.domain.model.ReminderType
import app.clearsms.domain.model.SubCategory
import app.clearsms.domain.model.TransactionType
import app.clearsms.domain.model.amount
import app.clearsms.domain.model.date
import app.clearsms.domain.model.merchant
import app.clearsms.domain.model.transactionType
import app.clearsms.domain.parser.BalanceStatement
import app.clearsms.domain.parser.DeliveryParser
import app.clearsms.domain.parser.OtpParser
import app.clearsms.domain.parser.ReminderParser
import app.clearsms.domain.parser.ReminderTypeClassifier
import app.clearsms.domain.parser.SenderNameResolver
import app.clearsms.domain.parser.TotalLimitStatement
import app.clearsms.domain.parser.TransactionParser
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** Default [MessageRepository] backed by Room and the domain pipeline. */
class MessageRepositoryImpl(
    private val database: ClearSmsDatabase,
    private val categorizer: MessageCategorizer,
    private val bundledRuleLoader: BundledRuleLoader,
    private val json: Json,
    private val otpParser: OtpParser = OtpParser(),
    private val transactionParser: TransactionParser = TransactionParser(),
    private val reminderParser: ReminderParser = ReminderParser(),
    private val deliveryParser: DeliveryParser = DeliveryParser(),
    /** Platform hook syncing deletions to the system SMS provider (null in tests). */
    private val systemSmsDeleter: SystemSmsDeleter? = null,
    private val systemSmsReadWriter: SystemSmsReadWriter? = null,
    /** Platform hook re-inserting restored bin rows into the provider (null in tests). */
    private val systemSmsReinserter: SystemSmsReinserter? = null,
    /** Platform hook cancelling shade notifications for read/deleted messages (null in tests). */
    private val readNotificationCanceler: ReadNotificationCanceler? = null,
    /** Page size for [recategorizeAll]; overridable so tests can hit batch boundaries. */
    private val recategorizePageSize: Int = RECATEGORIZE_PAGE_SIZE,
    /**
     * The user's blocked keywords (see
     * [app.clearsms.data.prefs.BlockedKeywords]); a matching incoming body
     * is routed to the bin or dropped at ingestion. Defaults to none.
     */
    private val blockedKeywords: suspend () -> Set<String> = { emptySet() },
    /**
     * Whether the recycle bin is enabled - decides whether a keyword-blocked
     * message rests in the bin or is dropped outright, mirroring the app's
     * committed-delete semantics. Default matches the settings default (on).
     */
    private val recycleBinEnabled: suspend () -> Boolean = { true },
) : MessageRepository {
    /** Types rule-extract reminders from body evidence (see [reminderFromExtracts]). */
    private val reminderTypeClassifier = ReminderTypeClassifier()

    private val messageDao get() = database.messageDao()
    private val accountDao get() = database.accountDao()
    private val transactionDao get() = database.transactionDao()
    private val reminderDao get() = database.reminderDao()
    private val ruleDao get() = database.ruleDao()
    private val draftDao get() = database.draftDao()
    private val threadPinDao get() = database.threadPinDao()

    /**
     * Test seam: invoked inside the ingestion transaction after the derived
     * rows are written, so tests can prove a mid-derivation failure rolls the
     * message back too. Always null in production.
     */
    internal var ingestionFailpointForTest: (suspend () -> Unit)? = null

    override fun observeInbox(
        category: Category?,
        unreadOnly: Boolean,
    ): Flow<List<MessageEntity>> = messageDao.observeInbox(category, unreadOnly)

    override fun observeThread(threadId: Long): Flow<List<MessageEntity>> = messageDao.observeThread(threadId)

    override fun pagedInbox(
        category: Category?,
        unreadOnly: Boolean,
    ): PagingSource<Int, InboxThreadRow> = messageDao.pagingInbox(category, unreadOnly)

    override suspend fun draftFor(threadId: Long): String? = draftDao.forThread(threadId)?.text

    override suspend fun saveDraft(
        threadId: Long,
        text: String,
    ) {
        if (text.isBlank()) {
            draftDao.delete(threadId)
        } else {
            draftDao.upsert(DraftEntity(threadId = threadId, text = text, updatedAt = System.currentTimeMillis()))
        }
    }

    override suspend fun setPinned(
        threadIds: List<Long>,
        pinned: Boolean,
    ) {
        if (threadIds.isEmpty()) return
        val senders =
            SqliteChunker.chunk(threadIds).flatMap { messageDao.normalizedSendersForThreads(it) }.distinct()
        if (senders.isEmpty()) return
        if (pinned) {
            val now = System.currentTimeMillis()
            threadPinDao.upsertAll(senders.map { ThreadPinEntity(normalizedSender = it, pinnedAt = now) })
        } else {
            SqliteChunker.chunk(senders).forEach { threadPinDao.deleteBySenders(it) }
        }
    }

    override suspend fun pinnedCountInThreads(threadIds: List<Long>): Int {
        if (threadIds.isEmpty()) return 0
        val senders =
            SqliteChunker.chunk(threadIds).flatMap { messageDao.normalizedSendersForThreads(it) }.distinct()
        return SqliteChunker.chunk(senders).sumOf { threadPinDao.countBySenders(it) }
    }

    override fun pagedThread(threadId: Long): PagingSource<Int, MessageEntity> = messageDao.pagingThread(threadId)

    override suspend fun firstInThread(threadId: Long): MessageEntity? = messageDao.firstInThread(threadId)

    override suspend fun inboxThreadIds(
        category: Category?,
        unreadOnly: Boolean,
    ): List<Long> = messageDao.inboxThreadIds(category, unreadOnly)

    override suspend fun messageIdsInThread(threadId: Long): List<Long> = messageDao.messageIdsInThread(threadId)

    override suspend fun positionInThread(
        threadId: Long,
        messageId: Long,
    ): Int = messageDao.newerCountInThread(threadId, messageId)

    override suspend fun bodiesInOrder(ids: List<Long>): List<String> = SqliteChunker.chunk(ids).flatMap { messageDao.bodiesFor(it) }

    override suspend fun lastSubscriptionIdInThread(threadId: Long): Int? = messageDao.lastSubscriptionIdInThread(threadId)

    override suspend fun distinctSubscriptionIds(): List<Int> = messageDao.distinctSubscriptionIds()

    override fun observeUnreadCounts(): Flow<List<CategoryUnreadCount>> = messageDao.observeUnreadCounts()

    override fun search(query: String): Flow<List<MessageEntity>> =
        when (val match = SearchQueryFormat.toFtsMatch(query)) {
            null -> flowOf(emptyList())
            else -> messageDao.search(match)
        }

    override fun pagedSearch(
        query: String,
        category: Category?,
        cutoffMs: Long?,
    ): PagingSource<Int, MessageEntity> =
        when (val match = SearchQueryFormat.toFtsMatch(query)) {
            null -> EmptyPagingSource()
            else -> messageDao.pagingSearch(match, category, cutoffMs)
        }

    override fun observeArchived(): Flow<List<MessageEntity>> = messageDao.observeArchived()

    override suspend fun archivedThreadIds(): List<Long> = messageDao.archivedThreadIds()

    override suspend fun markRead(
        messageId: Long,
        read: Boolean,
    ) {
        messageDao.markRead(messageId, read)
        syncReadToProvider(messageDao.systemSmsIdsFor(listOf(messageId)), read)
        if (read) cancelNotificationsForRead(listOf(messageId))
    }

    override suspend fun delete(messageId: Long) = deleteMessages(listOf(messageId))

    override suspend fun deleteMessages(ids: List<Long>) {
        if (ids.isEmpty()) return
        val chunks = SqliteChunker.chunk(ids)
        // Collect provider ids and delete our rows atomically; the provider
        // sync happens after commit - losing our copy but keeping the
        // provider row (crash in between) self-heals via the unique
        // systemSmsId re-import, the reverse would not.
        val (systemIds, threadIds) =
            database.withTransaction {
                val collectedSystem = chunks.flatMap { messageDao.systemSmsIdsFor(it) }
                val collectedThreads = chunks.flatMap { messageDao.threadIdsFor(it) }.distinct()
                chunks.forEach { messageDao.deleteByIds(it) }
                collectedSystem to collectedThreads
            }
        deleteFromProvider(systemIds)
        // A deleted message is no longer "new": its OTP / transaction / scam
        // notifications go with it (this is what makes the OTP auto-delete
        // path clear its notification too).
        readNotificationCanceler?.cancelFor(ids)
        cancelFullyReadThreadNotifications(threadIds)
    }

    override suspend fun deleteThreads(threadIds: List<Long>) {
        if (threadIds.isEmpty()) return
        val chunks = SqliteChunker.chunk(threadIds)
        val (systemIds, unreadIds) =
            database.withTransaction {
                val collectedSystem = chunks.flatMap { messageDao.systemSmsIdsForThreads(it) }
                // Only unread messages can still own per-message notifications
                // (read transitions cancel them), so collect just those before
                // the rows disappear.
                val collectedUnread = chunks.flatMap { messageDao.unreadMessageIdsInThreads(it) }
                chunks.forEach { messageDao.deleteByThreadIds(it) }
                collectedSystem to collectedUnread
            }
        deleteFromProvider(systemIds)
        readNotificationCanceler?.cancelFor(unreadIds)
        readNotificationCanceler?.cancelThreads(threadIds)
    }

    /** Forwards provider ids to the platform deleter in bounded chunks. */
    private fun deleteFromProvider(systemIds: List<Long>) {
        val deleter = systemSmsDeleter ?: return
        SqliteChunker.chunk(systemIds).forEach { deleter.deleteBySystemIds(it) }
    }

    // region undoable delete / recycle bin

    override suspend fun stageDeleteMessages(ids: List<Long>): List<Long> {
        if (ids.isEmpty()) return emptyList()
        val chunks = SqliteChunker.chunk(ids)
        val now = System.currentTimeMillis()
        // Soft-delete atomically; the rows keep their systemSmsId so the
        // deferred provider deletion (and an undo) need no bookkeeping
        // beyond the flags themselves.
        val (staged, threadIds) =
            database.withTransaction {
                val live = chunks.flatMap { messageDao.liveIds(it) }
                val threads = chunks.flatMap { messageDao.threadIdsFor(it) }.distinct()
                SqliteChunker.chunk(live).forEach { messageDao.stageDelete(it, now) }
                live to threads
            }
        // Deleted messages are no longer "new": exactly the cancellation the
        // hard-delete path performs. Undo never re-posts notifications.
        readNotificationCanceler?.cancelFor(staged)
        cancelFullyReadThreadNotifications(threadIds)
        return staged
    }

    override suspend fun stageDeleteThreads(threadIds: List<Long>): List<Long> {
        if (threadIds.isEmpty()) return emptyList()
        val ids = SqliteChunker.chunk(threadIds).flatMap { messageDao.liveIdsInThreads(it) }
        val staged = stageDeleteMessages(ids)
        readNotificationCanceler?.cancelThreads(threadIds)
        return staged
    }

    override suspend fun undoStagedDelete(ids: List<Long>) {
        if (ids.isEmpty()) return
        database.withTransaction {
            SqliteChunker.chunk(ids).forEach { messageDao.undoDelete(it) }
        }
    }

    override suspend fun commitStagedDelete(
        ids: List<Long>,
        toBin: Boolean,
    ) {
        if (ids.isEmpty()) return
        val chunks = SqliteChunker.chunk(ids)
        // Provider first: a crash after the provider deletion but before the
        // flag/row write leaves providerDeletePending set, and the next
        // launch re-issues a delete for already-gone provider ids - a no-op.
        // The reverse order could resurrect the message in other SMS apps.
        val systemIds = chunks.flatMap { messageDao.pendingSystemIdsFor(it) }
        deleteFromProvider(systemIds)
        database.withTransaction {
            if (toBin) {
                chunks.forEach { messageDao.clearProviderPending(it) }
            } else {
                chunks.forEach { messageDao.deleteByIds(it) }
            }
        }
    }

    override suspend fun commitAllPendingDeletes(toBin: Boolean) {
        commitStagedDelete(messageDao.pendingCommitIds(), toBin)
    }

    override fun observeBin(): Flow<List<MessageEntity>> = messageDao.observeBin()

    override suspend fun binMessageIds(): List<Long> = messageDao.binIds()

    override suspend fun restoreFromBin(ids: List<Long>): BinRestoreResult {
        if (ids.isEmpty()) return BinRestoreResult(restored = 0, reinserted = 0)
        val rows = SqliteChunker.chunk(ids).flatMap { messageDao.getByIds(it) }.filter { it.deletedAt != null }
        var reinserted = 0
        for (row in rows) {
            // The pre-deletion provider row is gone (deleted at commit), so
            // a restore writes a FRESH provider row; on failure the message
            // still comes back in-app, just without a provider mapping.
            val newSystemId =
                if (row.isOutgoing) {
                    systemSmsReinserter?.reinsertSent(row.sender, row.body, row.timestamp)
                } else {
                    systemSmsReinserter?.reinsertInbox(row.sender, row.body, row.timestamp, row.isRead)
                }
            if (newSystemId != null) reinserted++
            messageDao.restoreRow(row.id, newSystemId)
        }
        return BinRestoreResult(restored = rows.size, reinserted = reinserted)
    }

    override suspend fun deleteForever(ids: List<Long>) = deleteMessages(ids)

    override suspend fun purgeExpiredBin(cutoffMs: Long): Int {
        val expired = messageDao.expiredBinIds(cutoffMs)
        deleteMessages(expired)
        return expired.size
    }

    // endregion

    override suspend fun countOtpOlderThan(cutoffMs: Long): Int = messageDao.countOlderThan(Category.OTP, cutoffMs)

    override suspend fun deleteOtpOlderThan(cutoffMs: Long): Int {
        // Eligibility is category == OTP, nothing else: extractedOtp alone
        // does not qualify a message (an IMPORTANT bank alert carrying a code
        // must survive). Deletion reuses deleteMessages - the one batched
        // transaction + provider-sync path - rather than a second mechanism.
        val ids = messageDao.idsOlderThan(Category.OTP, cutoffMs)
        deleteMessages(ids)
        return ids.size
    }

    override suspend fun setReadForMessages(
        ids: List<Long>,
        read: Boolean,
    ) {
        if (ids.isEmpty()) return
        val chunks = SqliteChunker.chunk(ids)
        val systemIds =
            database.withTransaction {
                val collected = chunks.flatMap { messageDao.systemSmsIdsFor(it) }
                chunks.forEach { messageDao.setReadForIds(it, read) }
                collected
            }
        syncReadToProvider(systemIds, read)
        if (read) cancelNotificationsForRead(ids)
    }

    override suspend fun setReadForThreads(
        threadIds: List<Long>,
        read: Boolean,
    ) {
        if (threadIds.isEmpty()) return
        val chunks = SqliteChunker.chunk(threadIds)
        val (systemIds, unreadIds) =
            database.withTransaction {
                val collectedSystem = chunks.flatMap { messageDao.systemSmsIdsForThreads(it) }
                // Captured BEFORE the update: only the previously unread
                // messages may still own per-message notifications, and after
                // the write the distinction is gone.
                val collectedUnread =
                    if (read) chunks.flatMap { messageDao.unreadMessageIdsInThreads(it) } else emptyList()
                chunks.forEach { messageDao.setReadForThreads(it, read) }
                collectedSystem to collectedUnread
            }
        syncReadToProvider(systemIds, read)
        if (read) {
            readNotificationCanceler?.cancelFor(unreadIds)
            // The whole thread is read by definition of this operation.
            readNotificationCanceler?.cancelThreads(threadIds)
        }
    }

    /**
     * Cancels the notifications belonging to messages that just became read:
     * their per-message notifications unconditionally, and each owning
     * thread's message notification only once the thread has NO unread
     * messages left (a partially read thread keeps its notification).
     */
    private suspend fun cancelNotificationsForRead(messageIds: List<Long>) {
        val canceler = readNotificationCanceler ?: return
        canceler.cancelFor(messageIds)
        val threadIds = SqliteChunker.chunk(messageIds).flatMap { messageDao.threadIdsFor(it) }.distinct()
        cancelFullyReadThreadNotifications(threadIds)
    }

    /** Cancels thread message notifications for the subset of [threadIds] with zero unread messages. */
    private suspend fun cancelFullyReadThreadNotifications(threadIds: List<Long>) {
        val canceler = readNotificationCanceler ?: return
        if (threadIds.isEmpty()) return
        val withUnread =
            SqliteChunker.chunk(threadIds).flatMap { messageDao.threadIdsWithUnread(it) }.toSet()
        val fullyRead = threadIds.filterNot { it in withUnread }
        if (fullyRead.isNotEmpty()) canceler.cancelThreads(fullyRead)
    }

    /**
     * Mirrors a read-state change into the system SMS provider so it survives
     * re-import / reinstall and stays consistent with other SMS apps. No-ops
     * off the default app (the writer guards that) or when nothing mapped to a
     * provider row.
     */
    private fun syncReadToProvider(
        systemIds: List<Long>,
        read: Boolean,
    ) {
        val writer = systemSmsReadWriter ?: return
        if (systemIds.isEmpty()) return
        SqliteChunker.chunk(systemIds).forEach { writer.setReadBySystemIds(it, read) }
    }

    override suspend fun archiveThreads(
        threadIds: List<Long>,
        archived: Boolean,
    ) {
        if (threadIds.isEmpty()) return
        database.withTransaction {
            SqliteChunker.chunk(threadIds).forEach { messageDao.setArchivedForThreads(it, archived) }
        }
    }

    override suspend fun unreadCountInThreads(threadIds: List<Long>): Int =
        SqliteChunker.chunk(threadIds).sumOf { messageDao.unreadCountInThreads(it) }

    override suspend fun archive(
        messageId: Long,
        archived: Boolean,
    ) = messageDao.setArchived(messageId, archived)

    override suspend fun insertIncoming(
        sender: String,
        body: String,
        timestampMs: Long,
        systemSmsId: Long?,
    ): MessageEntity = ingestIncoming(sender, body, timestampMs, systemSmsId).entity

    override suspend fun ingestIncoming(
        sender: String,
        body: String,
        timestampMs: Long,
        systemSmsId: Long?,
    ): MessageRepository.IncomingIngest {
        // Blocked keywords are checked FIRST: a matching message must never
        // reach the inbox, notifications, or the finance derivations below.
        if (BlockedKeywords.matches(body, blockedKeywords())) {
            return ingestKeywordBlocked(sender, body, timestampMs, systemSmsId)
        }
        // Classification is pure CPU plus rule reads; only the writes below
        // need atomicity.
        val enriched = classify(rulesSnapshot(), sender, body)
        val normalized = SenderNormalizer.normalize(sender)
        // Message + derived transaction/account/reminder rows commit together:
        // a failure mid-derivation must never leave a message without its
        // finance rows (or vice versa). Retrying the delivery then re-runs the
        // whole unit; the import path gets the same guarantee from
        // persistImportedPage, whose unique systemSmsId index makes retries
        // no-ops.
        return database.withTransaction {
            val threadId = messageDao.threadIdFor(normalized) ?: ((messageDao.maxThreadId() ?: 0L) + 1L)
            val blocked = isSenderBlocked(normalized)

            val entity =
                MessageEntity(
                    threadId = threadId,
                    sender = sender,
                    normalizedSender = normalized,
                    body = body,
                    timestamp = timestampMs,
                    systemSmsId = systemSmsId,
                    category = enriched.result.category,
                    subCategory = enriched.result.subCategory,
                    extractedOtp = enriched.otpCode,
                    extractedDataJson = encodeExtracted(enriched.extracted),
                    isBlockedSender = blocked,
                )
            // IGNORE (not REPLACE) on the unique systemSmsId index: a
            // concurrent catch-up import may have committed this provider row
            // first. Replacing would delete the import's row (new id, lost
            // read-state, orphaned derived rows); instead the existing row is
            // returned marked duplicate, and the import path - which by
            // definition sees this just-arrived row as post-watermark -
            // carries the notification.
            val id = messageDao.insertIgnore(entity)
            if (id == -1L && systemSmsId != null) {
                val existing = messageDao.bySystemSmsId(systemSmsId)
                if (existing != null) {
                    return@withTransaction MessageRepository.IncomingIngest(existing, duplicate = true)
                }
            }
            // A -1 without a surviving row cannot happen inside this write
            // transaction (nulls are exempt from the unique index), but a
            // plain insert guarantees the message is never dropped either way.
            val rowId = if (id == -1L) messageDao.insert(entity) else id
            persistDerived(rowId, timestampMs, enriched)
            ingestionFailpointForTest?.invoke()
            MessageRepository.IncomingIngest(entity.copy(id = rowId), duplicate = false)
        }
    }

    /**
     * Ingests a keyword-blocked incoming message following the app's delete
     * semantics: with the recycle bin ON the row is born soft-deleted
     * (deletedAt set at ingest) and rests in the bin; with the bin OFF no
     * row is written at all. Either way the system-provider copy is removed
     * (like a committed delete), NO derived rows are produced - no
     * transactions, accounts or reminders - and the returned entity carries
     * a non-null deletedAt so [app.clearsms.notification.IncomingMessageRouter]
     * and the catch-up fresh filter stay silent about it.
     */
    private suspend fun ingestKeywordBlocked(
        sender: String,
        body: String,
        timestampMs: Long,
        systemSmsId: Long?,
    ): MessageRepository.IncomingIngest {
        // Classification still runs (pure CPU) so a binned message shows an
        // honest category if the user opens the bin - but nothing is derived.
        val enriched = classify(rulesSnapshot(), sender, body)
        val normalized = SenderNormalizer.normalize(sender)
        val binned = recycleBinEnabled()
        val entity =
            MessageEntity(
                threadId = 0L,
                sender = sender,
                normalizedSender = normalized,
                body = body,
                timestamp = timestampMs,
                // Read: a binned message must never count as unread anywhere.
                isRead = true,
                systemSmsId = systemSmsId,
                category = enriched.result.category,
                subCategory = enriched.result.subCategory,
                extractedOtp = enriched.otpCode,
                extractedDataJson = encodeExtracted(enriched.extracted),
                deletedAt = timestampMs,
                providerDeletePending = true,
            )
        if (!binned) {
            // Dropped outright - exactly what a committed delete with the
            // bin off does. The provider copy goes too.
            deleteFromProvider(listOfNotNull(systemSmsId))
            return MessageRepository.IncomingIngest(entity, duplicate = false)
        }
        val stored =
            database.withTransaction {
                val threadId = messageDao.threadIdFor(normalized) ?: ((messageDao.maxThreadId() ?: 0L) + 1L)
                val row = entity.copy(threadId = threadId)
                val id = messageDao.insertIgnore(row)
                if (id == -1L && systemSmsId != null) {
                    messageDao.bySystemSmsId(systemSmsId)
                } else {
                    row.copy(id = if (id == -1L) messageDao.insert(row) else id)
                }
            }
        // Same commit the undo-window delete performs: the provider row is
        // deleted and the app row rests in the bin (pending flag cleared).
        stored?.let { commitStagedDelete(listOf(it.id), toBin = true) }
        return MessageRepository.IncomingIngest(stored ?: entity, duplicate = false)
    }

    override suspend fun recategorizeAll(onProgress: suspend (processed: Int, total: Int) -> Unit): Int {
        // The rules snapshot is decoded ONCE (same optimization as the bulk
        // import) - re-decoding per message made a full re-sort O(N×R).
        val snapshot = rulesSnapshot()
        val total = messageDao.count()
        onProgress(0, total)
        var processed = 0
        var afterId = 0L
        while (true) {
            val page = messageDao.pageAfter(afterId, recategorizePageSize)
            if (page.isEmpty()) break
            // One transaction per page (mirrors persistImportedPage): a page
            // either fully commits or fully rolls back, so cancelling a
            // running re-sort never leaves a message updated without its
            // derived rows refreshed.
            database.withTransaction {
                for (message in page) {
                    val enriched = classify(snapshot, message.sender, message.body)
                    messageDao.update(
                        message.copy(
                            category = enriched.result.category,
                            subCategory = enriched.result.subCategory,
                            extractedOtp = enriched.otpCode,
                            extractedDataJson = encodeExtracted(enriched.extracted),
                        ),
                    )
                    // Reminders AND transactions are REFRESHED (deleted + re-derived
                    // inside this page transaction) so existing rows pick up parser
                    // and rule fixes - corrected titles, amounts, categories - and
                    // stale rows from messages that no longer derive anything
                    // disappear. Delete-before-insert keeps the run idempotent (a
                    // message never owns two transaction rows), and finance totals
                    // stay intact because every surviving message re-derives the
                    // same amounts. User-entered data survives: the transaction
                    // note is carried onto the re-derived row, and account rows
                    // (which hold user-set card limits) are upserted, never deleted.
                    reminderDao.deleteByRawSmsId(message.id)
                    val previousNote = transactionDao.findByRawSmsId(message.id)?.note
                    transactionDao.deleteByRawSmsId(message.id)
                    persistDerived(message.id, message.timestamp, enriched, preservedNote = previousNote)
                }
            }
            processed += page.size
            afterId = page.last().id
            onProgress(processed, total)
        }
        return processed
    }

    override suspend fun setBlocked(
        sender: String,
        blocked: Boolean,
    ) = messageDao.setBlockedSender(SenderNormalizer.normalize(sender), blocked)

    // region bulk import

    /**
     * Newest stored message timestamp - the import's notification watermark.
     * Null on an empty database (fresh install), which the importer treats
     * as "everything is old history": the initial onboarding import must
     * stay silent end-to-end.
     */
    internal suspend fun newestTimestamp(): Long? = messageDao.maxTimestamp()

    /**
     * Decodes the full rule set once. The categorization pipeline re-queries
     * and re-decodes rules for every message otherwise, which is O(N×R) over
     * an import; a snapshot makes it O(R).
     */
    internal suspend fun rulesSnapshot(): RulesSnapshot {
        bundledRuleLoader.ensureLoaded()
        return RulesSnapshot(
            userRules = definitions(RuleSources.USER),
            builtinRules = definitions(RuleSources.BUILTIN) + definitions(RuleSources.COMMUNITY),
        )
    }

    /**
     * Pure CPU classification against a pre-decoded [snapshot]; performs no
     * database writes, so it is safe to run concurrently across a page.
     */
    internal fun classify(
        snapshot: RulesSnapshot,
        sender: String,
        body: String,
    ): Enriched = enrich(sender, body, snapshot.userRules, snapshot.builtinRules)

    /**
     * Persists one import page in a single transaction using batch inserts.
     *
     * Rows whose [ImportedSmsRow.systemSmsId] already exists are skipped by
     * the unique index (IGNORE strategy), and their derived transaction /
     * reminder rows are skipped with them - re-processing a page can never
     * duplicate messages or double finance totals. Skipping also covers a
     * live delivery that beat this import to the row: the receiver already
     * notified it, so the importer must not count it as fresh.
     *
     * @return the messages actually inserted, with their Room ids.
     */
    internal suspend fun persistImportedPage(page: List<ImportedSmsRow>): List<MessageEntity> {
        if (page.isEmpty()) return emptyList()
        // Keyword-blocked incoming rows follow the same delete semantics as
        // the live path: bin ON -> inserted born-deleted (resting in the
        // bin, no derived rows); bin OFF -> not inserted at all. Their
        // provider copies are removed after the page commits.
        val keywords = blockedKeywords()
        val binEnabled = if (keywords.isEmpty()) false else recycleBinEnabled()
        val droppedSystemIds = mutableListOf<Long>()
        val binnedIds = mutableListOf<Long>()
        val inserted =
            database.withTransaction {
                var maxThreadId = messageDao.maxThreadId() ?: 0L
                val threadIds = HashMap<String, Long>()
                val blockedBySender = HashMap<String, Boolean>()
                val entities =
                    page.map { row ->
                        val normalized = SenderNormalizer.normalize(row.sender)
                        val threadId =
                            threadIds.getOrPut(normalized) {
                                messageDao.threadIdFor(normalized) ?: ++maxThreadId
                            }
                        val enriched = row.enriched
                        if (enriched != null) {
                            val keywordBlocked = BlockedKeywords.matches(row.body, keywords)
                            MessageEntity(
                                threadId = threadId,
                                sender = row.sender,
                                normalizedSender = normalized,
                                body = row.body,
                                timestamp = row.timestampMs,
                                isRead = if (keywordBlocked) true else row.isRead,
                                category = enriched.result.category,
                                subCategory = enriched.result.subCategory,
                                extractedOtp = enriched.otpCode,
                                extractedDataJson = encodeExtracted(enriched.extracted),
                                isBlockedSender = blockedBySender.getOrPut(normalized) { isSenderBlocked(normalized) },
                                systemSmsId = row.systemSmsId,
                                deletedAt = if (keywordBlocked) row.timestampMs else null,
                                providerDeletePending = keywordBlocked,
                            )
                        } else {
                            // Outgoing (sent) message: stored as a read personal
                            // message, right-aligned via the persisted direction.
                            MessageEntity(
                                threadId = threadId,
                                sender = row.sender,
                                normalizedSender = normalized,
                                body = row.body,
                                timestamp = row.timestampMs,
                                isRead = true,
                                category = Category.PERSONAL,
                                systemSmsId = row.systemSmsId,
                                isOutgoing = true,
                                deliveryStatus =
                                    if (row.delivered) DeliveryStatus.DELIVERED else DeliveryStatus.SENT,
                            )
                        }
                    }
                val toInsert =
                    entities.map { entity ->
                        if (entity.deletedAt != null && !binEnabled) {
                            // Bin off: the row is dropped, never inserted.
                            entity.systemSmsId?.let { droppedSystemIds += it }
                            null
                        } else {
                            entity
                        }
                    }
                val ids = messageDao.insertAllIgnore(toInsert.filterNotNull())
                val insertedList = ArrayList<MessageEntity>(entities.size)
                var idIndex = 0
                toInsert.forEachIndexed { index, entity ->
                    if (entity == null) return@forEachIndexed
                    val id = ids[idIndex++]
                    if (id == -1L) return@forEachIndexed
                    insertedList += entity.copy(id = id)
                    if (entity.deletedAt != null) {
                        // Keyword-binned: rests in the bin, derives NOTHING.
                        binnedIds += id
                        return@forEachIndexed
                    }
                    val row = page[index]
                    row.enriched?.let { persistDerived(id, row.timestampMs, it) }
                }
                insertedList
            }
        // Provider copies of keyword-blocked rows are removed after the page
        // commits - the same order the committed-delete path uses.
        commitStagedDelete(binnedIds, toBin = true)
        deleteFromProvider(droppedSystemIds)
        return inserted
    }

    // endregion

    // region pipeline

    internal data class Enriched(
        val result: CategorizationResult,
        val extracted: Map<String, String>,
        val otpCode: String?,
        val transaction: ParsedTransaction?,
        val reminder: ParsedReminder?,
        /** Relative dates resolve against the message timestamp at persist time. */
        val delivery: ParsedDelivery? = null,
        /** Balance-only statement: refreshes the account, never a transaction. */
        val balanceStatement: BalanceStatement? = null,
        /** Issuer-confirmed TOTAL limit: refreshes the card, never a transaction. */
        val totalLimit: TotalLimitStatement? = null,
    )

    /** Runs categorizer + parsers and merges rule extracts with parser output. */
    private fun enrich(
        sender: String,
        body: String,
        userRules: List<RuleDefinition>,
        builtinRules: List<RuleDefinition>,
    ): Enriched {
        // Same evaluation-input cap as the categorizer (see
        // MessageCategorizer.MAX_EVAL_BODY_LENGTH): the OTP/transaction/
        // reminder parsers below are regex-driven too, so they must never see
        // an unbounded body. Only evaluation is capped - the stored row keeps
        // the full text.
        val evalBody = body.take(MessageCategorizer.MAX_EVAL_BODY_LENGTH)
        val result = categorizer.categorize(sender, body, userRules, builtinRules)
        val extracts = result.extracted

        val otpCode =
            extracts["otp_code"]
                ?: if (result.category == Category.OTP) otpParser.parse(evalBody)?.code else null

        val parsedTx = transactionParser.parse(sender, evalBody)
        val transaction =
            when {
                // Statement/bill notices ("Statement is sent...", "Total of Rs X
                // ... is due") describe money OWED - they must never become a
                // transaction, whether from the parser or from rule extracts.
                // They stay reminders (see the reminder path below).
                transactionParser.isStatementNotice(evalBody) -> null
                // FAILED payments moved no money: no transaction, whether
                // from the parser (already null there) or from rule extracts.
                transactionParser.isFailedPayment(evalBody) -> null
                parsedTx != null -> mergeTransaction(parsedTx, result.typed, result.subCategory)
                result.subCategory in TRANSACTION_DERIVING_SUBCATEGORIES ->
                    transactionFromExtracts(extracts, result.typed, result.subCategory, sender, evalBody)
                else -> null
            }

        // Delivery expectations only come from messages the categorizer
        // already recognized as deliveries - the parser is not allowed to
        // introduce a fresh source of false positives.
        val delivery =
            if (result.subCategory == SubCategory.DELIVERY) {
                deliveryParser.parse(sender, evalBody)
            } else {
                null
            }

        val parsedReminder = if (delivery == null) reminderParser.parse(sender, evalBody) else null
        val reminder =
            when {
                parsedReminder != null -> mergeReminder(parsedReminder, result.typed, extracts)
                result.subCategory == SubCategory.BILL -> reminderFromExtracts(sender, evalBody, extracts, result.typed)
                else -> null
                // A reminder without a due date is not actionable; this also
                // keeps transaction confirmations (SubCategory.TRANSACTION)
                // from doubling as reminders unless they genuinely carry a
                // due date, e.g. a card statement. The one dateless shape
                // allowed through is a "bill ... is generated" notice
                // carrying its amount - the freshly issued bill IS the
                // obligation (see ReminderParser.isGeneratedBillNotice).
            }?.takeIf {
                it.dueDate != null || (it.totalDue != null && reminderParser.isGeneratedBillNotice(evalBody))
            }

        // A balance statement reports STATE, not movement: derived only when
        // no transaction exists (a debit quoting "Avl Bal" keeps its balance
        // as a secondary transaction field instead). Rule extracts win; the
        // parser's statement shapes are the fallback. Requiring a
        // balance-carrying extract set or a parser match keeps unrelated
        // messages from ever refreshing an account.
        val balanceStatement =
            if (transaction == null) {
                balanceFromExtracts(sender, evalBody, extracts, result.typed)
                    ?: transactionParser.parseBalanceStatement(sender, evalBody)
            } else {
                null
            }

        // An issuer-confirmed total-limit statement ("changed from INR X to
        // INR Y", "Your new limit is ...") refreshes the card's total so
        // outstanding/utilization stay derivable without any manual entry.
        val totalLimit = transactionParser.parseTotalLimit(sender, evalBody)

        val merged = LinkedHashMap<String, String>()
        transaction?.let { tx ->
            merged["amount"] = tx.amount.toString()
            merged["type"] = if (tx.type == TransactionType.DEBIT) "debit" else "credit"
            tx.accountLast4?.let { merged["account_last4"] = it }
            tx.bankName?.let { merged["bank"] = it }
            tx.merchantName?.let { merged["merchant"] = it }
            tx.balance?.let { merged["balance"] = it.toString() }
            tx.availableLimit?.let { merged["available_limit"] = it.toString() }
            tx.referenceNumber?.let { merged["reference"] = it }
            // A USD/EUR/... spend keeps its currency on record so the amount
            // is never silently read as INR (the entity itself has no
            // currency column yet - this is the audit trail until it does).
            transactionParser.foreignCurrency(evalBody)?.let { merged["currency"] = it }
        }
        // Balance-only details feed the same "balance"/"account_last4"/"bank"
        // keys the UI and the parsed notification already render blue.
        balanceStatement?.let { statement ->
            merged["balance"] = statement.balance.toString()
            statement.accountLast4?.let { merged["account_last4"] = it }
            statement.bankName?.let { merged["bank"] = it }
        }
        totalLimit?.let { statement ->
            merged["total_limit"] = statement.totalLimit.toString()
            statement.accountLast4?.let { merged.putIfAbsent("account_last4", it) }
            statement.bankName?.let { merged.putIfAbsent("bank", it) }
        }
        otpCode?.let { merged["otp_code"] = it }
        // Rule-extracted values win over parser heuristics for shared keys.
        merged.putAll(extracts)
        // ...except the merchant: the transaction above already applied the
        // rule-over-parser precedence WITH normalization, so re-stamp it -
        // otherwise a raw rule capture ("XX6894- RD Installment-Jul 2026")
        // would land in extractedDataJson and resurface in the UI.
        transaction?.let { tx ->
            tx.merchantName?.let { merged["merchant"] = it } ?: merged.remove("merchant")
        }
        // ...and the reminder fields: the reminder object above already
        // merged rule extracts with parser output, TYPED (its due date is a
        // real date, not a raw "03-Jul-26" capture) and invariant-checked
        // (totalDue >= minDue). Re-stamping keeps extractedDataJson - what
        // the parsed notification and the conversation card render - in
        // lockstep with the Alerts row, and normalizes the due date to ISO.
        reminder?.let { rem ->
            rem.totalDue?.let { merged["total_due"] = it.toString() } ?: merged.remove("total_due")
            rem.minDue?.let { merged["min_due"] = it.toString() } ?: merged.remove("min_due")
            rem.dueDate?.let { merged["due_date"] = it.toString() }
            rem.accountLast4?.let { merged.putIfAbsent("account_last4", it) }
            rem.bankName?.let { merged.putIfAbsent("bank", it) }
            rem.label?.let { merged.putIfAbsent("label", it) }
        }

        return Enriched(
            result = result,
            extracted = merged,
            otpCode = otpCode,
            transaction = transaction,
            reminder = reminder,
            delivery = delivery,
            balanceStatement = balanceStatement,
            totalLimit = totalLimit,
        )
    }

    private suspend fun persistDerived(
        messageId: Long,
        timestampMs: Long,
        enriched: Enriched,
        /**
         * User note from a previous derivation of the same message, carried
         * onto the re-derived row by the re-sort refresh path.
         */
        preservedNote: String? = null,
    ) {
        enriched.transaction?.let { tx ->
            val accountNumber = tx.accountLast4 ?: ""
            val canonicalBank = SenderNameResolver.canonicalize(tx.bankName).orEmpty()
            // Account-creation guardrail: an account/card row may only
            // carry the name of a plausible financial institution or
            // wallet. Merchant names, payment channels (CRED) and
            // ecommerce brands (Flipkart) - including ones a rule extract
            // re-injected - are stripped to a blank issuer, so the row
            // stays claimable by the real bank instead of spawning a
            // bogus "Flipkart bank account".
            val bankName =
                if (SenderNameResolver.isPlausibleIssuer(canonicalBank)) canonicalBank else ""
            // Deduplication runs BEFORE any account write: a suppressed
            // cross-bank UPI echo (the provider bank re-announcing the
            // user's own bank's payment) must never create a transaction
            // OR an account under the provider bank.
            val unlinked =
                TransactionEntity(
                    amount = tx.amount,
                    type = tx.type,
                    merchantName = tx.merchantName,
                    accountNumber = accountNumber,
                    bankName = bankName,
                    accountId = null,
                    timestamp = timestampMs,
                    balance = tx.balance,
                    referenceNumber = tx.referenceNumber,
                    category = tx.merchantCategory,
                    rawSmsId = messageId,
                    note = preservedNote,
                )
            // Banks alert the same payment more than once (spend alert +
            // statement line), and a UPI payment is echoed by a SECOND bank
            // (the UPI app's provider). When an existing row already records
            // this payment (see [TransactionDeduplication]) the rows are
            // collapsed instead of double-counting the money.
            val duplicate = findExistingDuplicate(unlinked)
            when {
                duplicate == null -> {
                    val accountId = resolveAccountId(tx, accountNumber, bankName, timestampMs)
                    transactionDao.insert(unlinked.copy(accountId = accountId))
                }
                duplicate.bankName.isNotEmpty() && bankName.isNotEmpty() && duplicate.bankName != bankName ->
                    collapseCrossBankEcho(duplicate, unlinked, tx, timestampMs)
                else -> {
                    val accountId = resolveAccountId(tx, accountNumber, bankName, timestampMs)
                    val candidate = unlinked.copy(accountId = accountId)
                    transactionDao.update(
                        TransactionDeduplication.collapse(duplicate, candidate).copy(id = duplicate.id),
                    )
                }
            }
        }
        // Balance-only messages update the account WITHOUT fabricating a
        // transaction row. Gated hard: the message must name the account
        // (last-4) and a plausible issuer - a merchant or shortcode balance
        // mention can never create or touch an account.
        if (enriched.transaction == null) {
            enriched.balanceStatement?.let { statement ->
                val accountNumber = statement.accountLast4 ?: return@let
                val canonicalBank = SenderNameResolver.canonicalize(statement.bankName).orEmpty()
                if (!SenderNameResolver.isPlausibleIssuer(canonicalBank)) return@let
                upsertAccountBalance(
                    accountNumber = accountNumber,
                    bankName = canonicalBank,
                    accountType = statement.accountType,
                    balance = statement.balance,
                    timestampMs = timestampMs,
                )
            }
        }
        // A confirmed total-limit statement updates the card's total limit -
        // the sole source of the figure now that manual entry is gone. Same
        // guardrails as balances: the message must name the card (last-4)
        // and a plausible issuer.
        enriched.totalLimit?.let { statement ->
            val accountNumber = statement.accountLast4 ?: return@let
            val canonicalBank = SenderNameResolver.canonicalize(statement.bankName).orEmpty()
            if (!SenderNameResolver.isPlausibleIssuer(canonicalBank)) return@let
            upsertAccountBalance(
                accountNumber = accountNumber,
                bankName = canonicalBank,
                accountType = AccountType.CREDIT_CARD,
                balance = null,
                timestampMs = timestampMs,
                totalLimit = statement.totalLimit,
            )
        }
        enriched.reminder?.let { reminder ->
            reminderDao.insert(
                ReminderEntity(
                    type = reminder.type,
                    dueDate = reminder.dueDate?.toEpochMs(),
                    totalDue = reminder.totalDue,
                    minDue = reminder.minDue,
                    accountLast4 = reminder.accountLast4,
                    bankName = reminder.bankName,
                    label = reminder.label,
                    rawSmsId = messageId,
                    createdAt = timestampMs,
                ),
            )
        }
        enriched.delivery?.let { delivery ->
            // "today"/"tomorrow" resolve against the MESSAGE date, not the
            // current clock - imports of old messages stay correct.
            val messageDate =
                Instant
                    .ofEpochMilli(timestampMs)
                    .atZone(ZoneId.systemDefault())
                    .toLocalDate()
            reminderDao.insert(
                ReminderEntity(
                    type = ReminderType.DELIVERY,
                    // Null for a dispatch notice with no stated arrival: the
                    // card shows as an undated upcoming delivery.
                    dueDate = delivery.expectedDate(messageDate)?.toEpochMs(),
                    bankName = delivery.merchant,
                    label = delivery.reference,
                    rawSmsId = messageId,
                    createdAt = timestampMs,
                ),
            )
        }
    }

    /**
     * An already-persisted row recording the same payment as [candidate],
     * or null. Candidates are narrowed by the DAO (same reference on the
     * same account last-4 at any time distance, or same amount/type/last-4
     * inside the tier-2 window - bank-agnostic, so cross-bank echoes
     * surface) and each pairing is confirmed by [TransactionDeduplication].
     * A ref-LESS cross-bank pairing is additionally vetoed when BOTH banks
     * hold independent transaction evidence for the tail: two genuine
     * accounts at two banks sharing a last-4 and receiving the same amount
     * is exactly the false positive that tier must never merge.
     */
    private suspend fun findExistingDuplicate(candidate: TransactionEntity): TransactionEntity? {
        val normalizedRef = TransactionDeduplication.normalizedReference(candidate.referenceNumber)
        val byReference =
            if (normalizedRef != null) {
                transactionDao.findByReference(normalizedRef, candidate.accountNumber)
            } else {
                emptyList()
            }
        val nearby =
            transactionDao.findNearby(
                amount = candidate.amount,
                type = candidate.type,
                accountNumber = candidate.accountNumber,
                fromTs = candidate.timestamp - TransactionDeduplication.NEAR_DUPLICATE_WINDOW_MS,
                toTs = candidate.timestamp + TransactionDeduplication.NEAR_DUPLICATE_WINDOW_MS,
            )
        for (existing in (byReference + nearby).distinctBy { it.id }) {
            val duplicate =
                when {
                    TransactionDeduplication.isReferenceDuplicate(existing, candidate) -> true
                    TransactionDeduplication.isNearDuplicateAlert(existing, candidate) -> true
                    TransactionDeduplication.isCrossBankReferenceEcho(existing, candidate) -> true
                    TransactionDeduplication.isCrossBankNearEcho(existing, candidate) ->
                        !bothBanksHoldTail(existing, candidate)
                    else -> false
                }
            if (duplicate) return existing
        }
        return null
    }

    /**
     * Whether BOTH banks of a ref-less cross-bank pairing have transaction
     * evidence for the tail beyond the pair itself - the two-genuine-accounts
     * shape a near-echo merge must never touch.
     */
    private suspend fun bothBanksHoldTail(
        existing: TransactionEntity,
        candidate: TransactionEntity,
    ): Boolean {
        val existingEvidence =
            transactionDao.countByBankAndTail(existing.bankName, existing.accountNumber, existing.id)
        if (existingEvidence == 0) return false
        val candidateEvidence =
            transactionDao.countByBankAndTail(candidate.bankName, candidate.accountNumber, existing.id)
        return candidateEvidence > 0
    }

    /**
     * Resolves the owning account ONCE, at ingestion. With a named issuer
     * the account is upserted and its id used. With a blank issuer NO
     * account is ever created: the transaction attaches to an existing
     * account only when exactly one named bank holds that last-4, otherwise
     * it stays unattached - a nameless account row is never the answer. The
     * one exception to "no last-4, no account" is a curated standalone CARD
     * product or WALLET issuer (see issuerKeyedAccountId): their money SMS
     * carry no digits at all, yet the issuer identifies the account exactly.
     */
    private suspend fun resolveAccountId(
        tx: ParsedTransaction,
        accountNumber: String,
        bankName: String,
        timestampMs: Long,
    ): Long? =
        when {
            accountNumber.isEmpty() -> issuerKeyedAccountId(tx, bankName, timestampMs)
            bankName.isNotEmpty() -> upsertAccount(tx, accountNumber, bankName, timestampMs)
            else -> soleAccountIdForTail(accountNumber, tx.accountType)
        }

    /**
     * Collapses a cross-bank UPI echo pair into ONE transaction under ONE
     * bank. The surviving bank is the one with a real account relationship
     * - other transactions already attributed to that (bank, last-4); the
     * UPI provider's echo has none (see
     * [TransactionDeduplication.crossBankSurvivor]). Only the winner's
     * account is (up)serted; if the LOSING side already spawned an account
     * that nothing else references - no other transaction, no balance or
     * limit ever reported - that phantom row is reaped, so the provider
     * bank never surfaces in Finance.
     */
    private suspend fun collapseCrossBankEcho(
        existing: TransactionEntity,
        candidate: TransactionEntity,
        tx: ParsedTransaction,
        timestampMs: Long,
    ) {
        val existingEvidence =
            transactionDao.countByBankAndTail(existing.bankName, existing.accountNumber, existing.id)
        val candidateEvidence =
            transactionDao.countByBankAndTail(candidate.bankName, candidate.accountNumber, existing.id)
        val winner =
            TransactionDeduplication.crossBankSurvivor(existing, candidate, existingEvidence, candidateEvidence)
        val loser = if (winner === existing) candidate else existing
        val accountId =
            winner.accountId
                ?: upsertAccountBalance(
                    accountNumber = winner.accountNumber,
                    bankName = winner.bankName,
                    accountType = tx.accountType,
                    balance = winner.balance,
                    timestampMs = timestampMs,
                )
        transactionDao.update(
            TransactionDeduplication
                .collapseCrossBank(winner, loser)
                .copy(id = existing.id, accountId = accountId),
        )
        // Reap the phantom: the losing side's account, when the echo was its
        // only evidence. A row holding ANY other transaction or a reported
        // balance/limit is a genuine account and stays.
        val orphanId = if (loser === existing) existing.accountId else null
        orphanId?.let { id ->
            if (id == accountId) return@let
            val account = accountDao.findById(id) ?: return@let
            if (transactionDao.countByAccountId(id, existing.id) > 0) return@let
            if (account.lastKnownBalance != null || account.creditLimit != null || account.availableLimit != null) return@let
            accountDao.deleteById(id)
        }
    }

    private suspend fun upsertAccount(
        tx: ParsedTransaction,
        accountNumber: String,
        bankName: String,
        timestampMs: Long,
    ): Long = upsertAccountBalance(accountNumber, bankName, tx.accountType, tx.balance, timestampMs, tx.availableLimit)

    /**
     * The account a bank-less transaction may attach to: exactly ONE named
     * bank must hold this last-4 (preferring the row matching [accountType]
     * when a bank has several). Null when no bank or several banks share
     * the tail - attaching by number alone is how cross-bank contamination
     * happened.
     */
    private suspend fun soleAccountIdForTail(
        accountNumber: String,
        accountType: AccountType,
    ): Long? {
        val named = accountDao.findByNumber(accountNumber).filter { it.bankName.isNotBlank() }
        if (named.isEmpty() || named.map { it.bankName }.distinct().size > 1) return null
        return (named.firstOrNull { it.type == accountType } ?: named.first()).id
    }

    /**
     * The account for a money message whose SMS carries NO account digits
     * at all - two curated shapes send exactly that:
     *  - co-branded card products (Scapia Federal): "txn ... on your Scapia
     *    Federal Visa credit card was successful", never a last-4;
     *  - wallet products (Pluxee): "Your Pluxee Card has been successfully
     *    credited with Rs.X towards Meal Wallet", never a last-4.
     *
     * The rule, deliberately narrow:
     *  - the resolved issuer must be a curated standalone CARD product
     *    ([SenderNameResolver.isCardProductIssuer]) with a body that reads
     *    as a card ([AccountType.CREDIT_CARD]), OR a curated WALLET issuer
     *    ([SenderNameResolver.isWalletIssuer]) with a body that reads as a
     *    wallet ([AccountType.WALLET]) - a digit-less SAVINGS debit stays
     *    unattached exactly as before;
     *  - when the issuer already has exactly ONE account of that type (a
     *    template change later adds a last-4, say), the money attaches
     *    to it;
     *  - when it has none, ONE account is created under a stable synthetic
     *    key ([SenderNameResolver.syntheticAccountKey]) - the issuer alone
     *    identifies the card/wallet, and the user's money belongs on it,
     *    not in an unattached limbo;
     *  - several same-type accounts of the same issuer are ambiguous:
     *    unattached (null).
     *
     * Known failure mode: if the issuer later starts quoting a real last-4,
     * the first such message creates a second (digit-keyed) account next to
     * the synthetic one and history splits between them. Accepted: the
     * attach-to-sole-existing branch handles the reverse (and far likelier)
     * order, and merchants still can never become accounts - the issuer
     * must survive the curated card-product/wallet-issuer check.
     */
    private suspend fun issuerKeyedAccountId(
        tx: ParsedTransaction,
        bankName: String,
        timestampMs: Long,
    ): Long? {
        if (bankName.isEmpty()) return null
        val ownedType =
            when {
                tx.accountType == AccountType.CREDIT_CARD && SenderNameResolver.isCardProductIssuer(bankName) ->
                    AccountType.CREDIT_CARD
                tx.accountType == AccountType.WALLET && SenderNameResolver.isWalletIssuer(bankName) ->
                    AccountType.WALLET
                else -> return null
            }
        val owned = accountDao.findByBank(bankName).filter { it.type == ownedType }
        return when {
            owned.size > 1 -> null
            owned.size == 1 ->
                upsertAccountBalance(
                    accountNumber = owned.single().accountNumber,
                    bankName = bankName,
                    accountType = ownedType,
                    balance = tx.balance,
                    timestampMs = timestampMs,
                    availableLimit = tx.availableLimit,
                )
            else ->
                upsertAccountBalance(
                    accountNumber = SenderNameResolver.syntheticAccountKey(bankName),
                    bankName = bankName,
                    accountType = ownedType,
                    balance = tx.balance,
                    timestampMs = timestampMs,
                    availableLimit = tx.availableLimit,
                )
        }
    }

    /**
     * Creates or refreshes an account row from either a transaction or a
     * balance-only statement - ONE mechanism, so the timestamp ordering
     * (older messages never clobber a newer balance) and the blank-bank
     * claim behave identically for both sources. Returns the row id of the
     * created or updated account, so transactions link to it explicitly.
     */
    private suspend fun upsertAccountBalance(
        accountNumber: String,
        bankName: String,
        accountType: AccountType,
        balance: Double?,
        timestampMs: Long,
        /** Issuer-reported available credit limit; follows the same ordering rules as [balance]. */
        availableLimit: Double? = null,
        /** Issuer-confirmed TOTAL credit limit; follows the same ordering rules as [balance]. */
        totalLimit: Double? = null,
    ): Long {
        val existing = accountDao.find(accountNumber, bankName)
        if (existing == null) {
            // A pre-resolution row of the same account carries a blank bank
            // name: claim and name it instead of spawning a duplicate card.
            val blank = if (bankName.isNotEmpty()) accountDao.findBlankBank(accountNumber, accountType) else null
            if (blank != null) {
                accountDao.update(
                    blank.copy(
                        bankName = bankName,
                        lastKnownBalance =
                            if (timestampMs >= blank.lastUpdated) {
                                balance ?: blank.lastKnownBalance
                            } else {
                                blank.lastKnownBalance
                            },
                        availableLimit =
                            if (timestampMs >= blank.lastUpdated) {
                                availableLimit ?: blank.availableLimit
                            } else {
                                blank.availableLimit
                            },
                        creditLimit =
                            if (timestampMs >= blank.lastUpdated) {
                                totalLimit ?: blank.creditLimit
                            } else {
                                blank.creditLimit
                            },
                        lastUpdated = maxOf(timestampMs, blank.lastUpdated),
                    ),
                )
                return blank.id
            }
            return accountDao.insert(
                AccountEntity(
                    accountNumber = accountNumber,
                    bankName = bankName,
                    type = accountType,
                    lastKnownBalance = balance,
                    availableLimit = availableLimit,
                    creditLimit = totalLimit,
                    lastUpdated = timestampMs,
                ),
            )
        }
        if (timestampMs >= existing.lastUpdated) {
            accountDao.update(
                existing.copy(
                    lastKnownBalance = balance ?: existing.lastKnownBalance,
                    availableLimit = availableLimit ?: existing.availableLimit,
                    creditLimit = totalLimit ?: existing.creditLimit,
                    lastUpdated = timestampMs,
                ),
            )
        }
        return existing.id
    }

    /**
     * Balance statement assembled from rule extracts (the "balance" key,
     * plus optional account/bank). Only consulted for messages WITHOUT a
     * transaction; the issuer must survive the plausible-issuer check or the
     * bank stays null (and persistence then skips the account entirely).
     */
    private fun balanceFromExtracts(
        sender: String,
        evalBody: String,
        extracts: Map<String, String>,
        typed: Map<String, ExtractedValue>,
    ): BalanceStatement? {
        val balance = typed.amount("balance") ?: return null
        val bank = extracts["bank"] ?: SenderNameResolver.bankNameFor(sender, evalBody)
        return BalanceStatement(
            balance = balance,
            accountLast4 = extracts["account_last4"],
            bankName = bank?.takeIf { SenderNameResolver.isPlausibleIssuer(it, evalBody) },
            accountType = AccountType.SAVINGS,
        )
    }

    private suspend fun definitions(source: String): List<RuleDefinition> =
        ruleDao.getEnabledBySource(source).mapNotNull { it.toDefinition(json) }

    private suspend fun isSenderBlocked(normalizedSender: String): Boolean = messageDao.isSenderBlocked(normalizedSender)

    /**
     * Overlays rule-extracted values onto the parser's transaction.
     *
     * The engine already resolved each extract to its typed value - amounts
     * parsed, the merchant normalized (see [ExtractedValue]) - so this is a
     * pure precedence merge: a typed rule value wins over the parser's
     * heuristic. A merchant capture that was pure reference noise normalized
     * to null and falls back to the parser's (already clean) title, keeping
     * a rule's precise capture from ever REGRESSING it.
     */
    private fun mergeTransaction(
        parsed: ParsedTransaction,
        typed: Map<String, ExtractedValue>,
        subCategory: SubCategory?,
    ): ParsedTransaction =
        parsed.copy(
            amount = typed.amount("amount") ?: parsed.amount,
            type = typed.transactionType("type") ?: parsed.type,
            merchantName = typed.merchant("merchant") ?: parsed.merchantName,
            accountLast4 = (typed["account_last4"] as? ExtractedValue.Text)?.raw ?: parsed.accountLast4,
            bankName = (typed["bank"] as? ExtractedValue.Text)?.raw ?: parsed.bankName,
            balance = typed.amount("balance") ?: parsed.balance,
            availableLimit = typed.amount("available_limit") ?: parsed.availableLimit,
            merchantCategory = subCategory.toMerchantCategory() ?: parsed.merchantCategory,
        )

    /**
     * Builds a transaction purely from rule extracts when the parser found
     * none. Amount AND type extracts are mandatory, so balance-only rules
     * (NPS investment-value statements, data-balance alerts) can never
     * create a transaction.
     */
    private fun transactionFromExtracts(
        extracts: Map<String, String>,
        typed: Map<String, ExtractedValue>,
        subCategory: SubCategory?,
        sender: String,
        body: String,
    ): ParsedTransaction? {
        val amount = typed.amount("amount") ?: return null
        val type = typed.transactionType("type") ?: return null
        return ParsedTransaction(
            amount = amount,
            type = type,
            // A recharge / bill payment / top-up has no third-party merchant -
            // the biller IS the sender - so the title falls back to the resolved
            // sender brand ("Airtel") rather than a generic phrase. A merchant
            // named in the body still wins. It goes in the MERCHANT slot, not
            // bankName, so the account-creation guardrail is untouched.
            merchantName =
                typed.merchant("merchant")
                    ?: extracts["operator"]
                    ?: subCategory
                        ?.takeIf { it in BILLER_BRANDED_SUBCATEGORIES }
                        ?.let { SenderNameResolver.brandNameFor(sender, body) },
            accountLast4 = extracts["account_last4"],
            bankName = extracts["bank"],
            balance = typed.amount("balance"),
            availableLimit = typed.amount("available_limit"),
            merchantCategory = subCategory.toMerchantCategory() ?: MerchantCategory.OTHER,
            // The body's own wording decides the account kind - a rule-matched
            // card spend ("on your ... credit card was successful") must land
            // on the card, never on a phantom savings account.
            accountType = transactionParser.accountTypeOf(body),
        )
    }

    /**
     * Spend category implied by the rule's sub-category: a recharge rule
     * always yields a RECHARGE spend, an investment/mutual-fund rule an
     * INVESTMENT spend - regardless of what the body-keyword heuristic says.
     */
    private fun SubCategory?.toMerchantCategory(): MerchantCategory? =
        when (this) {
            SubCategory.RECHARGE -> MerchantCategory.RECHARGE
            SubCategory.INVESTMENT, SubCategory.MUTUAL_FUND -> MerchantCategory.INVESTMENT
            else -> null
        }

    /**
     * Rule extracts win over parser heuristics per field. A rule's generic
     * "amount" extract is the amount DUE - it backfills the total when
     * neither the rule nor the parser produced an explicit total (the ICICI
     * Pru premium rule captures the premium as "amount"; dropping it was why
     * premiums surfaced with no amount). [ensureTotalNotBelowMin] re-applies
     * the totalDue >= minDue invariant AFTER the merge, because raw rule
     * captures bypass the parser's own [ReminderParser] resolution.
     */
    private fun mergeReminder(
        parsed: ParsedReminder,
        typed: Map<String, ExtractedValue>,
        extracts: Map<String, String>,
    ): ParsedReminder =
        ensureTotalNotBelowMin(
            parsed.copy(
                dueDate = typed.date("due_date") ?: parsed.dueDate,
                totalDue = typed.amount("total_due") ?: parsed.totalDue ?: typed.amount("amount"),
                minDue = typed.amount("min_due") ?: parsed.minDue,
                accountLast4 = (typed["account_last4"] as? ExtractedValue.Text)?.raw ?: parsed.accountLast4,
                bankName = (typed["bank"] as? ExtractedValue.Text)?.raw ?: parsed.bankName,
                label = extracts["label"] ?: parsed.label,
            ),
        )

    private fun reminderFromExtracts(
        sender: String,
        evalBody: String,
        extracts: Map<String, String>,
        typed: Map<String, ExtractedValue>,
    ): ParsedReminder? {
        // Undated candidates are not actionable reminders - an amount alone
        // (e.g. a reimbursement-claim SMS) must not become an Alerts card.
        val dueDate = typed.date("due_date") ?: return null
        return ensureTotalNotBelowMin(
            ParsedReminder(
                // A rule only says "this is a bill-like reminder"; the TYPE still
                // comes from the body's evidence (a card mini-statement matched
                // by a bill rule is a credit-card bill, not a generic bill).
                type = reminderTypeClassifier.classify(sender, evalBody) ?: ReminderType.OTHER,
                dueDate = dueDate,
                // A rule's generic "amount" is the amount DUE - the headline
                // total, never the minimum (the Alerts card and the parsed
                // notification lead with the total).
                totalDue = typed.amount("total_due") ?: typed.amount("amount"),
                minDue = typed.amount("min_due"),
                accountLast4 = extracts["account_last4"],
                bankName = extracts["bank"],
                label = extracts["label"],
            ),
        )
    }

    /**
     * Post-merge totalDue >= minDue invariant: a merged "total" below the
     * minimum is a mis-capture (a minimum-due phrase landing in a total
     * slot), so the total is dropped rather than stored wrong - mirroring
     * [ReminderParser]'s own resolution, which raw rule captures bypass.
     */
    private fun ensureTotalNotBelowMin(reminder: ParsedReminder): ParsedReminder {
        val total = reminder.totalDue
        val min = reminder.minDue
        if (total == null || min == null || total >= min) return reminder
        return reminder.copy(totalDue = null)
    }

    private fun encodeExtracted(extracted: Map<String, String>): String? =
        if (extracted.isEmpty()) {
            null
        } else {
            json.encodeToString(MapSerializer(String.serializer(), String.serializer()), extracted)
        }

    private fun LocalDate.toEpochMs(): Long = atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()

    // endregion

    companion object {
        /** Messages per re-categorization transaction - large enough to amortize
         * the commit, small enough that progress ticks and cancellation stay
         * responsive on a 14k-message inbox. */
        const val RECATEGORIZE_PAGE_SIZE = 200

        /**
         * Sub-categories whose rule extracts may derive a transaction on
         * their own (no parser match needed): plain transactions, prepaid
         * recharges, and investment/mutual-fund contributions - all real
         * money movements the user asked to see in Finance. Amount + type
         * extracts remain mandatory (see transactionFromExtracts), so
         * balance-only rules under these sub-categories never create rows.
         */
        private val TRANSACTION_DERIVING_SUBCATEGORIES =
            setOf(
                SubCategory.TRANSACTION,
                SubCategory.RECHARGE,
                SubCategory.INVESTMENT,
                SubCategory.MUTUAL_FUND,
            )

        /**
         * Sub-categories where the BILLER is the counterparty, so a transaction
         * with no merchant in its body is titled with the resolved sender brand
         * ("Airtel") instead of a generic phrase.
         */
        private val BILLER_BRANDED_SUBCATEGORIES =
            setOf(
                SubCategory.RECHARGE,
                SubCategory.BILL,
            )
    }
}

/** Immutable decoded rule set, reused for a whole import run. */
internal data class RulesSnapshot(
    val userRules: List<RuleDefinition>,
    val builtinRules: List<RuleDefinition>,
)

/**
 * One system SMS provider row prepared for batch persistence.
 * [enriched] is null for outgoing (sent) messages, which skip classification.
 * [delivered] is only meaningful for outgoing rows (provider `STATUS_COMPLETE`).
 */
internal data class ImportedSmsRow(
    val systemSmsId: Long,
    val sender: String,
    val body: String,
    val timestampMs: Long,
    val isRead: Boolean,
    val enriched: MessageRepositoryImpl.Enriched?,
    val delivered: Boolean = false,
)

/** Page source that is always empty - the unsearchable-query fallback. */
private class EmptyPagingSource : PagingSource<Int, MessageEntity>() {
    override fun getRefreshKey(state: androidx.paging.PagingState<Int, MessageEntity>): Int? = null

    override suspend fun load(params: LoadParams<Int>): LoadResult<Int, MessageEntity> =
        LoadResult.Page(data = emptyList(), prevKey = null, nextKey = null)
}
