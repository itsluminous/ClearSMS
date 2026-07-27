package app.clearsms.data.repository

import androidx.paging.PagingSource
import androidx.room.withTransaction
import app.clearsms.data.db.AccountEntity
import app.clearsms.data.db.CategoryUnreadCount
import app.clearsms.data.db.ClearSmsDatabase
import app.clearsms.data.db.MessageEntity
import app.clearsms.data.db.ReminderEntity
import app.clearsms.data.db.TransactionEntity
import app.clearsms.data.rules.BundledRuleLoader
import app.clearsms.data.rules.RuleDefinition
import app.clearsms.data.rules.RuleSources
import app.clearsms.data.rules.toDefinition
import app.clearsms.domain.categorizer.MessageCategorizer
import app.clearsms.domain.model.CategorizationResult
import app.clearsms.domain.model.Category
import app.clearsms.domain.model.ParsedDelivery
import app.clearsms.domain.model.ParsedReminder
import app.clearsms.domain.model.ParsedTransaction
import app.clearsms.domain.model.ReminderType
import app.clearsms.domain.model.SubCategory
import app.clearsms.domain.model.TransactionType
import app.clearsms.domain.parser.DeliveryParser
import app.clearsms.domain.parser.OtpParser
import app.clearsms.domain.parser.ReminderParser
import app.clearsms.domain.parser.ReminderTypeClassifier
import app.clearsms.domain.parser.SenderNameResolver
import app.clearsms.domain.parser.TransactionParser
import kotlinx.coroutines.flow.Flow
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
    /** Page size for [recategorizeAll]; overridable so tests can hit batch boundaries. */
    private val recategorizePageSize: Int = RECATEGORIZE_PAGE_SIZE,
) : MessageRepository {
    /** Types rule-extract reminders from body evidence (see [reminderFromExtracts]). */
    private val reminderTypeClassifier = ReminderTypeClassifier()

    private val messageDao get() = database.messageDao()
    private val accountDao get() = database.accountDao()
    private val transactionDao get() = database.transactionDao()
    private val reminderDao get() = database.reminderDao()
    private val ruleDao get() = database.ruleDao()

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
    ): PagingSource<Int, MessageEntity> = messageDao.pagingInbox(category, unreadOnly)

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

    override fun observeUnreadCounts(): Flow<List<CategoryUnreadCount>> = messageDao.observeUnreadCounts()

    override fun search(query: String): Flow<List<MessageEntity>> = messageDao.search(query)

    override suspend fun markRead(
        messageId: Long,
        read: Boolean,
    ) = messageDao.markRead(messageId, read)

    override suspend fun delete(messageId: Long) = deleteMessages(listOf(messageId))

    override suspend fun deleteMessages(ids: List<Long>) {
        if (ids.isEmpty()) return
        val chunks = SqliteChunker.chunk(ids)
        // Collect provider ids and delete our rows atomically; the provider
        // sync happens after commit — losing our copy but keeping the
        // provider row (crash in between) self-heals via the unique
        // systemSmsId re-import, the reverse would not.
        val systemIds =
            database.withTransaction {
                val collected = chunks.flatMap { messageDao.systemSmsIdsFor(it) }
                chunks.forEach { messageDao.deleteByIds(it) }
                collected
            }
        deleteFromProvider(systemIds)
    }

    override suspend fun deleteThreads(threadIds: List<Long>) {
        if (threadIds.isEmpty()) return
        val chunks = SqliteChunker.chunk(threadIds)
        val systemIds =
            database.withTransaction {
                val collected = chunks.flatMap { messageDao.systemSmsIdsForThreads(it) }
                chunks.forEach { messageDao.deleteByThreadIds(it) }
                collected
            }
        deleteFromProvider(systemIds)
    }

    /** Forwards provider ids to the platform deleter in bounded chunks. */
    private fun deleteFromProvider(systemIds: List<Long>) {
        val deleter = systemSmsDeleter ?: return
        SqliteChunker.chunk(systemIds).forEach { deleter.deleteBySystemIds(it) }
    }

    override suspend fun setReadForMessages(
        ids: List<Long>,
        read: Boolean,
    ) {
        if (ids.isEmpty()) return
        database.withTransaction {
            SqliteChunker.chunk(ids).forEach { messageDao.setReadForIds(it, read) }
        }
    }

    override suspend fun setReadForThreads(
        threadIds: List<Long>,
        read: Boolean,
    ) {
        if (threadIds.isEmpty()) return
        database.withTransaction {
            SqliteChunker.chunk(threadIds).forEach { messageDao.setReadForThreads(it, read) }
        }
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
    ): MessageEntity {
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
                    category = enriched.result.category,
                    subCategory = enriched.result.subCategory,
                    extractedOtp = enriched.otpCode,
                    extractedDataJson = encodeExtracted(enriched.extracted),
                    isBlockedSender = blocked,
                )
            val id = messageDao.insert(entity)
            persistDerived(id, timestampMs, enriched)
            ingestionFailpointForTest?.invoke()
            entity.copy(id = id)
        }
    }

    override suspend fun recategorizeAll(onProgress: suspend (processed: Int, total: Int) -> Unit): Int {
        // The rules snapshot is decoded ONCE (same optimization as the bulk
        // import) — re-decoding per message made a full re-sort O(N×R).
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
                    // Reminders are REFRESHED (deleted + re-derived) so existing rows
                    // pick up parser fixes — amounts, labels, corrected types — and
                    // stale reminders from messages the parser now rejects disappear.
                    // Transactions keep the skip-existing guard: re-inserting them
                    // would double finance totals.
                    reminderDao.deleteByRawSmsId(message.id)
                    persistDerived(message.id, message.timestamp, enriched, skipExistingTransactions = true)
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
     * reminder rows are skipped with them — re-processing a page can never
     * duplicate messages or double finance totals.
     *
     * @return the number of messages actually inserted.
     */
    internal suspend fun persistImportedPage(page: List<ImportedSmsRow>): Int {
        if (page.isEmpty()) return 0
        return database.withTransaction {
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
                        MessageEntity(
                            threadId = threadId,
                            sender = row.sender,
                            normalizedSender = normalized,
                            body = row.body,
                            timestamp = row.timestampMs,
                            isRead = row.isRead,
                            category = enriched.result.category,
                            subCategory = enriched.result.subCategory,
                            extractedOtp = enriched.otpCode,
                            extractedDataJson = encodeExtracted(enriched.extracted),
                            isBlockedSender = blockedBySender.getOrPut(normalized) { isSenderBlocked(normalized) },
                            systemSmsId = row.systemSmsId,
                        )
                    } else {
                        // Outgoing (sent) message: stored as a read personal message.
                        MessageEntity(
                            threadId = threadId,
                            sender = row.sender,
                            normalizedSender = normalized,
                            body = row.body,
                            timestamp = row.timestampMs,
                            isRead = true,
                            category = Category.PERSONAL,
                            systemSmsId = row.systemSmsId,
                        )
                    }
                }
            val ids = messageDao.insertAllIgnore(entities)
            var inserted = 0
            ids.forEachIndexed { index, id ->
                if (id == -1L) return@forEachIndexed
                inserted++
                val row = page[index]
                row.enriched?.let { persistDerived(id, row.timestampMs, it) }
            }
            inserted
        }
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
        // an unbounded body. Only evaluation is capped — the stored row keeps
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
                parsedTx != null -> mergeTransaction(parsedTx, extracts)
                result.subCategory == SubCategory.TRANSACTION -> transactionFromExtracts(extracts)
                else -> null
            }

        // Delivery expectations only come from messages the categorizer
        // already recognized as deliveries — the parser is not allowed to
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
                parsedReminder != null -> mergeReminder(parsedReminder, extracts)
                result.subCategory == SubCategory.BILL -> reminderFromExtracts(sender, evalBody, extracts)
                else -> null
                // A reminder without a due date is not actionable; this also
                // keeps transaction confirmations (SubCategory.TRANSACTION)
                // from doubling as reminders unless they genuinely carry a
                // due date, e.g. a card statement.
            }?.takeIf { it.dueDate != null }

        val merged = LinkedHashMap<String, String>()
        transaction?.let { tx ->
            merged["amount"] = tx.amount.toString()
            merged["type"] = if (tx.type == TransactionType.DEBIT) "debit" else "credit"
            tx.accountLast4?.let { merged["account_last4"] = it }
            tx.bankName?.let { merged["bank"] = it }
            tx.merchantName?.let { merged["merchant"] = it }
            tx.balance?.let { merged["balance"] = it.toString() }
            tx.referenceNumber?.let { merged["reference"] = it }
        }
        reminder?.let { rem ->
            rem.totalDue?.let { merged["total_due"] = it.toString() }
            rem.minDue?.let { merged["min_due"] = it.toString() }
            rem.dueDate?.let { merged["due_date"] = it.toString() }
        }
        otpCode?.let { merged["otp_code"] = it }
        // Rule-extracted values win over parser heuristics for shared keys.
        merged.putAll(extracts)

        return Enriched(
            result = result,
            extracted = merged,
            otpCode = otpCode,
            transaction = transaction,
            reminder = reminder,
            delivery = delivery,
        )
    }

    private suspend fun persistDerived(
        messageId: Long,
        timestampMs: Long,
        enriched: Enriched,
        skipExistingTransactions: Boolean = false,
    ) {
        enriched.transaction?.let { tx ->
            if (!skipExistingTransactions || transactionDao.findByRawSmsId(messageId) == null) {
                val accountNumber = tx.accountLast4 ?: ""
                val bankName = SenderNameResolver.canonicalize(tx.bankName).orEmpty()
                if (accountNumber.isNotEmpty()) {
                    upsertAccount(tx, accountNumber, bankName, timestampMs)
                }
                transactionDao.insert(
                    TransactionEntity(
                        amount = tx.amount,
                        type = tx.type,
                        merchantName = tx.merchantName,
                        accountNumber = accountNumber,
                        bankName = bankName,
                        timestamp = timestampMs,
                        balance = tx.balance,
                        referenceNumber = tx.referenceNumber,
                        category = tx.merchantCategory,
                        rawSmsId = messageId,
                    ),
                )
            }
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
            // current clock — imports of old messages stay correct.
            val messageDate =
                Instant
                    .ofEpochMilli(timestampMs)
                    .atZone(ZoneId.systemDefault())
                    .toLocalDate()
            reminderDao.insert(
                ReminderEntity(
                    type = ReminderType.DELIVERY,
                    dueDate = delivery.expectedDate(messageDate).toEpochMs(),
                    bankName = delivery.merchant,
                    label = delivery.reference,
                    rawSmsId = messageId,
                    createdAt = timestampMs,
                ),
            )
        }
    }

    private suspend fun upsertAccount(
        tx: ParsedTransaction,
        accountNumber: String,
        bankName: String,
        timestampMs: Long,
    ) {
        val existing = accountDao.find(accountNumber, bankName)
        if (existing == null) {
            // A pre-resolution row of the same account carries a blank bank
            // name: claim and name it instead of spawning a duplicate card.
            val blank = if (bankName.isNotEmpty()) accountDao.findBlankBank(accountNumber, tx.accountType) else null
            if (blank != null) {
                accountDao.update(
                    blank.copy(
                        bankName = bankName,
                        lastKnownBalance =
                            if (timestampMs >= blank.lastUpdated) {
                                tx.balance ?: blank.lastKnownBalance
                            } else {
                                blank.lastKnownBalance
                            },
                        lastUpdated = maxOf(timestampMs, blank.lastUpdated),
                    ),
                )
            } else {
                accountDao.insert(
                    AccountEntity(
                        accountNumber = accountNumber,
                        bankName = bankName,
                        type = tx.accountType,
                        lastKnownBalance = tx.balance,
                        lastUpdated = timestampMs,
                    ),
                )
            }
        } else if (timestampMs >= existing.lastUpdated) {
            accountDao.update(
                existing.copy(
                    lastKnownBalance = tx.balance ?: existing.lastKnownBalance,
                    lastUpdated = timestampMs,
                ),
            )
        }
    }

    private suspend fun definitions(source: String): List<RuleDefinition> =
        ruleDao.getEnabledBySource(source).mapNotNull { it.toDefinition(json) }

    private suspend fun isSenderBlocked(normalizedSender: String): Boolean = messageDao.isSenderBlocked(normalizedSender)

    /** Overlays rule-extracted values onto the parser's transaction. */
    private fun mergeTransaction(
        parsed: ParsedTransaction,
        extracts: Map<String, String>,
    ): ParsedTransaction =
        parsed.copy(
            amount = extracts["amount"]?.toAmount() ?: parsed.amount,
            type = extracts["type"]?.toTransactionType() ?: parsed.type,
            merchantName = extracts["merchant"] ?: parsed.merchantName,
            accountLast4 = extracts["account_last4"] ?: parsed.accountLast4,
            bankName = extracts["bank"] ?: parsed.bankName,
            balance = extracts["balance"]?.toAmount() ?: parsed.balance,
        )

    /** Builds a transaction purely from rule extracts when the parser found none. */
    private fun transactionFromExtracts(extracts: Map<String, String>): ParsedTransaction? {
        val amount = extracts["amount"]?.toAmount() ?: return null
        val type = extracts["type"]?.toTransactionType() ?: return null
        return ParsedTransaction(
            amount = amount,
            type = type,
            merchantName = extracts["merchant"],
            accountLast4 = extracts["account_last4"],
            bankName = extracts["bank"],
            balance = extracts["balance"]?.toAmount(),
        )
    }

    private fun mergeReminder(
        parsed: ParsedReminder,
        extracts: Map<String, String>,
    ): ParsedReminder =
        parsed.copy(
            dueDate = extracts["due_date"]?.let { reminderParser.parseDate(it) } ?: parsed.dueDate,
            totalDue = extracts["total_due"]?.toAmount() ?: parsed.totalDue,
            minDue = extracts["min_due"]?.toAmount() ?: parsed.minDue,
            accountLast4 = extracts["account_last4"] ?: parsed.accountLast4,
            bankName = extracts["bank"] ?: parsed.bankName,
        )

    private fun reminderFromExtracts(
        sender: String,
        evalBody: String,
        extracts: Map<String, String>,
    ): ParsedReminder? {
        // Undated candidates are not actionable reminders — an amount alone
        // (e.g. a reimbursement-claim SMS) must not become an Alerts card.
        val dueDate = extracts["due_date"]?.let { reminderParser.parseDate(it) } ?: return null
        return ParsedReminder(
            // A rule only says "this is a bill-like reminder"; the TYPE still
            // comes from the body's evidence (a card mini-statement matched
            // by a bill rule is a credit-card bill, not a generic bill).
            type = reminderTypeClassifier.classify(sender, evalBody) ?: ReminderType.OTHER,
            dueDate = dueDate,
            totalDue = extracts["total_due"]?.toAmount(),
            minDue = extracts["min_due"]?.toAmount() ?: extracts["amount"]?.toAmount(),
            accountLast4 = extracts["account_last4"],
            bankName = extracts["bank"],
        )
    }

    private fun encodeExtracted(extracted: Map<String, String>): String? =
        if (extracted.isEmpty()) {
            null
        } else {
            json.encodeToString(MapSerializer(String.serializer(), String.serializer()), extracted)
        }

    private fun String.toAmount(): Double? = replace(",", "").toDoubleOrNull()

    private fun String.toTransactionType(): TransactionType? =
        when (lowercase()) {
            "debit" -> TransactionType.DEBIT
            "credit" -> TransactionType.CREDIT
            else -> null
        }

    private fun LocalDate.toEpochMs(): Long = atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()

    // endregion

    companion object {
        /** Messages per re-categorization transaction — large enough to amortize
         * the commit, small enough that progress ticks and cancellation stay
         * responsive on a 14k-message inbox. */
        const val RECATEGORIZE_PAGE_SIZE = 200
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
 */
internal data class ImportedSmsRow(
    val systemSmsId: Long,
    val sender: String,
    val body: String,
    val timestampMs: Long,
    val isRead: Boolean,
    val enriched: MessageRepositoryImpl.Enriched?,
)
