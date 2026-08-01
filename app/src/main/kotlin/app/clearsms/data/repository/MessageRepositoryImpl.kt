package app.clearsms.data.repository

import androidx.paging.PagingSource
import androidx.room.withTransaction
import app.clearsms.data.db.AccountEntity
import app.clearsms.data.db.CategoryUnreadCount
import app.clearsms.data.db.ClearSmsDatabase
import app.clearsms.data.db.DeliveryStatus
import app.clearsms.data.db.MessageEntity
import app.clearsms.data.db.ReminderEntity
import app.clearsms.data.db.TransactionEntity
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
    }

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

    override suspend fun countOtpOlderThan(cutoffMs: Long): Int = messageDao.countOlderThan(Category.OTP, cutoffMs)

    override suspend fun deleteOtpOlderThan(cutoffMs: Long): Int {
        // Eligibility is category == OTP, nothing else: extractedOtp alone
        // does not qualify a message (an IMPORTANT bank alert carrying a code
        // must survive). Deletion reuses deleteMessages — the one batched
        // transaction + provider-sync path — rather than a second mechanism.
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
    }

    override suspend fun setReadForThreads(
        threadIds: List<Long>,
        read: Boolean,
    ) {
        if (threadIds.isEmpty()) return
        val chunks = SqliteChunker.chunk(threadIds)
        val systemIds =
            database.withTransaction {
                val collected = chunks.flatMap { messageDao.systemSmsIdsForThreads(it) }
                chunks.forEach { messageDao.setReadForThreads(it, read) }
                collected
            }
        syncReadToProvider(systemIds, read)
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
                    // Reminders AND transactions are REFRESHED (deleted + re-derived
                    // inside this page transaction) so existing rows pick up parser
                    // and rule fixes — corrected titles, amounts, categories — and
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
                // Statement/bill notices ("Statement is sent...", "Total of Rs X
                // ... is due") describe money OWED — they must never become a
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
                parsedReminder != null -> mergeReminder(parsedReminder, result.typed, extracts)
                result.subCategory == SubCategory.BILL -> reminderFromExtracts(sender, evalBody, extracts, result.typed)
                else -> null
                // A reminder without a due date is not actionable; this also
                // keeps transaction confirmations (SubCategory.TRANSACTION)
                // from doubling as reminders unless they genuinely carry a
                // due date, e.g. a card statement.
            }?.takeIf { it.dueDate != null }

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
            // currency column yet — this is the audit trail until it does).
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
        // rule-over-parser precedence WITH normalization, so re-stamp it —
        // otherwise a raw rule capture ("XX6894- RD Installment-Jul 2026")
        // would land in extractedDataJson and resurface in the UI.
        transaction?.let { tx ->
            tx.merchantName?.let { merged["merchant"] = it } ?: merged.remove("merchant")
        }
        // ...and the reminder fields: the reminder object above already
        // merged rule extracts with parser output, TYPED (its due date is a
        // real date, not a raw "03-Jul-26" capture) and invariant-checked
        // (totalDue >= minDue). Re-stamping keeps extractedDataJson — what
        // the parsed notification and the conversation card render — in
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
            // ecommerce brands (Flipkart) — including ones a rule extract
            // re-injected — are stripped to a blank issuer, so the row
            // stays claimable by the real bank instead of spawning a
            // bogus "Flipkart bank account".
            val bankName =
                if (SenderNameResolver.isPlausibleIssuer(canonicalBank)) canonicalBank else ""
            // Resolve the owning account ONCE, here at ingestion. With a
            // named issuer the account is upserted and its id used. With a
            // blank issuer NO account is ever created: the transaction
            // attaches to an existing account only when exactly one named
            // bank holds that last-4, otherwise it stays unattached — a
            // nameless account row is never the answer. The one exception
            // to "no last-4, no account" is a curated standalone CARD
            // product (see issuerKeyedCardAccountId): its spend SMS carry
            // no digits at all, yet the issuer identifies the card exactly.
            val accountId =
                when {
                    accountNumber.isEmpty() -> issuerKeyedCardAccountId(tx, bankName, timestampMs)
                    bankName.isNotEmpty() -> upsertAccount(tx, accountNumber, bankName, timestampMs)
                    else -> soleAccountIdForTail(accountNumber, tx.accountType)
                }
            val candidate =
                TransactionEntity(
                    amount = tx.amount,
                    type = tx.type,
                    merchantName = tx.merchantName,
                    accountNumber = accountNumber,
                    bankName = bankName,
                    accountId = accountId,
                    timestamp = timestampMs,
                    balance = tx.balance,
                    referenceNumber = tx.referenceNumber,
                    category = tx.merchantCategory,
                    rawSmsId = messageId,
                    note = preservedNote,
                )
            // Banks alert the same payment more than once (spend alert +
            // statement line). When an existing row already records this
            // payment — same reference on the same account, or a twin alert
            // moments apart (see [TransactionDeduplication]) — the rows are
            // collapsed instead of double-counting the money.
            val duplicate = findExistingDuplicate(candidate)
            if (duplicate != null) {
                transactionDao.update(
                    TransactionDeduplication.collapse(duplicate, candidate).copy(id = duplicate.id),
                )
            } else {
                transactionDao.insert(candidate)
            }
        }
        // Balance-only messages update the account WITHOUT fabricating a
        // transaction row. Gated hard: the message must name the account
        // (last-4) and a plausible issuer — a merchant or shortcode balance
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
        // A confirmed total-limit statement updates the card's total limit —
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

    /**
     * An already-persisted row recording the same payment as [candidate],
     * or null. Candidates are narrowed by the DAO (same reference on the
     * same account at any time distance, or same amount/type/account inside
     * the tier-2 window) and each pairing is confirmed by
     * [TransactionDeduplication], which applies the balance / merchant /
     * account-link guards.
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
                bankName = candidate.bankName,
                fromTs = candidate.timestamp - TransactionDeduplication.NEAR_DUPLICATE_WINDOW_MS,
                toTs = candidate.timestamp + TransactionDeduplication.NEAR_DUPLICATE_WINDOW_MS,
            )
        return (byReference + nearby).firstOrNull { TransactionDeduplication.isDuplicate(it, candidate) }
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
     * the tail — attaching by number alone is how cross-bank contamination
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
     * The account for a card spend whose SMS carries NO account digits at
     * all — the shape co-branded card products (Scapia Federal) actually
     * send: "txn ... on your Scapia Federal Visa credit card was
     * successful", never a last-4.
     *
     * The rule, deliberately narrow:
     *  - the resolved issuer must be a curated standalone CARD product
     *    ([SenderNameResolver.isCardProductIssuer]) and the body must read
     *    as a card ([AccountType.CREDIT_CARD]) — a digit-less SAVINGS debit
     *    stays unattached exactly as before;
     *  - when the issuer already has exactly ONE card account (a template
     *    change later adds a last-4, say), the spend attaches to it;
     *  - when it has none, ONE card account is created under a stable
     *    synthetic key ([SenderNameResolver.syntheticAccountKey]) — the
     *    issuer alone identifies the card, and the user's spends belong on
     *    a card, not in an unattached limbo;
     *  - several cards of the same issuer are ambiguous: unattached (null).
     *
     * Known failure mode: if the issuer later starts quoting a real last-4,
     * the first such message creates a second (digit-keyed) card next to
     * the synthetic one and history splits between them. Accepted: the
     * attach-to-sole-existing branch handles the reverse (and far likelier)
     * order, and merchants still can never become accounts — the issuer
     * must survive the curated card-product check.
     */
    private suspend fun issuerKeyedCardAccountId(
        tx: ParsedTransaction,
        bankName: String,
        timestampMs: Long,
    ): Long? {
        if (bankName.isEmpty()) return null
        if (tx.accountType != AccountType.CREDIT_CARD) return null
        if (!SenderNameResolver.isCardProductIssuer(bankName)) return null
        val cards = accountDao.findByBank(bankName).filter { it.type == AccountType.CREDIT_CARD }
        return when {
            cards.size > 1 -> null
            cards.size == 1 ->
                upsertAccountBalance(
                    accountNumber = cards.single().accountNumber,
                    bankName = bankName,
                    accountType = AccountType.CREDIT_CARD,
                    balance = tx.balance,
                    timestampMs = timestampMs,
                    availableLimit = tx.availableLimit,
                )
            else ->
                upsertAccountBalance(
                    accountNumber = SenderNameResolver.syntheticAccountKey(bankName),
                    bankName = bankName,
                    accountType = AccountType.CREDIT_CARD,
                    balance = tx.balance,
                    timestampMs = timestampMs,
                    availableLimit = tx.availableLimit,
                )
        }
    }

    /**
     * Creates or refreshes an account row from either a transaction or a
     * balance-only statement — ONE mechanism, so the timestamp ordering
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
     * The engine already resolved each extract to its typed value — amounts
     * parsed, the merchant normalized (see [ExtractedValue]) — so this is a
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
            // A recharge / bill payment / top-up has no third-party merchant —
            // the biller IS the sender — so the title falls back to the resolved
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
            // The body's own wording decides the account kind — a rule-matched
            // card spend ("on your ... credit card was successful") must land
            // on the card, never on a phantom savings account.
            accountType = transactionParser.accountTypeOf(body),
        )
    }

    /**
     * Spend category implied by the rule's sub-category: a recharge rule
     * always yields a RECHARGE spend, an investment/mutual-fund rule an
     * INVESTMENT spend — regardless of what the body-keyword heuristic says.
     */
    private fun SubCategory?.toMerchantCategory(): MerchantCategory? =
        when (this) {
            SubCategory.RECHARGE -> MerchantCategory.RECHARGE
            SubCategory.INVESTMENT, SubCategory.MUTUAL_FUND -> MerchantCategory.INVESTMENT
            else -> null
        }

    /**
     * Rule extracts win over parser heuristics per field. A rule's generic
     * "amount" extract is the amount DUE — it backfills the total when
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
        // Undated candidates are not actionable reminders — an amount alone
        // (e.g. a reimbursement-claim SMS) must not become an Alerts card.
        val dueDate = typed.date("due_date") ?: return null
        return ensureTotalNotBelowMin(
            ParsedReminder(
                // A rule only says "this is a bill-like reminder"; the TYPE still
                // comes from the body's evidence (a card mini-statement matched
                // by a bill rule is a credit-card bill, not a generic bill).
                type = reminderTypeClassifier.classify(sender, evalBody) ?: ReminderType.OTHER,
                dueDate = dueDate,
                // A rule's generic "amount" is the amount DUE — the headline
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
     * slot), so the total is dropped rather than stored wrong — mirroring
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
        /** Messages per re-categorization transaction — large enough to amortize
         * the commit, small enough that progress ticks and cancellation stay
         * responsive on a 14k-message inbox. */
        const val RECATEGORIZE_PAGE_SIZE = 200

        /**
         * Sub-categories whose rule extracts may derive a transaction on
         * their own (no parser match needed): plain transactions, prepaid
         * recharges, and investment/mutual-fund contributions — all real
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

/** Page source that is always empty — the unsearchable-query fallback. */
private class EmptyPagingSource : PagingSource<Int, MessageEntity>() {
    override fun getRefreshKey(state: androidx.paging.PagingState<Int, MessageEntity>): Int? = null

    override suspend fun load(params: LoadParams<Int>): LoadResult<Int, MessageEntity> =
        LoadResult.Page(data = emptyList(), prevKey = null, nextKey = null)
}
