package app.clearsms.data.repository

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
import app.clearsms.domain.model.ParsedReminder
import app.clearsms.domain.model.ParsedTransaction
import app.clearsms.domain.model.ReminderType
import app.clearsms.domain.model.SubCategory
import app.clearsms.domain.model.TransactionType
import app.clearsms.domain.parser.OtpParser
import app.clearsms.domain.parser.ReminderParser
import app.clearsms.domain.parser.TransactionParser
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
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
) : MessageRepository {
    private val messageDao get() = database.messageDao()
    private val accountDao get() = database.accountDao()
    private val transactionDao get() = database.transactionDao()
    private val reminderDao get() = database.reminderDao()
    private val ruleDao get() = database.ruleDao()

    override fun observeInbox(
        category: Category?,
        unreadOnly: Boolean,
    ): Flow<List<MessageEntity>> = messageDao.observeInbox(category, unreadOnly)

    override fun observeThread(threadId: Long): Flow<List<MessageEntity>> = messageDao.observeThread(threadId)

    override fun observeUnreadCounts(): Flow<List<CategoryUnreadCount>> = messageDao.observeUnreadCounts()

    override fun search(query: String): Flow<List<MessageEntity>> = messageDao.search(query)

    override suspend fun markRead(
        messageId: Long,
        read: Boolean,
    ) = messageDao.markRead(messageId, read)

    override suspend fun delete(messageId: Long) = messageDao.deleteById(messageId)

    override suspend fun archive(
        messageId: Long,
        archived: Boolean,
    ) = messageDao.setArchived(messageId, archived)

    override suspend fun insertIncoming(
        sender: String,
        body: String,
        timestampMs: Long,
    ): MessageEntity {
        val enriched = classify(rulesSnapshot(), sender, body)
        val normalized = SenderNormalizer.normalize(sender)
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
        return entity.copy(id = id)
    }

    override suspend fun recategorizeAll() {
        val snapshot = rulesSnapshot()
        for (message in messageDao.getAll()) {
            val enriched = classify(snapshot, message.sender, message.body)
            messageDao.update(
                message.copy(
                    category = enriched.result.category,
                    subCategory = enriched.result.subCategory,
                    extractedOtp = enriched.otpCode,
                    extractedDataJson = encodeExtracted(enriched.extracted),
                ),
            )
            persistDerived(message.id, message.timestamp, enriched, skipExisting = true)
        }
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
    )

    /** Runs categorizer + parsers and merges rule extracts with parser output. */
    private fun enrich(
        sender: String,
        body: String,
        userRules: List<RuleDefinition>,
        builtinRules: List<RuleDefinition>,
    ): Enriched {
        val result = categorizer.categorize(sender, body, userRules, builtinRules)
        val extracts = result.extracted

        val otpCode =
            extracts["otp_code"]
                ?: if (result.category == Category.OTP) otpParser.parse(body)?.code else null

        val parsedTx = transactionParser.parse(sender, body)
        val transaction =
            when {
                parsedTx != null -> mergeTransaction(parsedTx, extracts)
                result.subCategory == SubCategory.TRANSACTION -> transactionFromExtracts(extracts)
                else -> null
            }

        val parsedReminder = reminderParser.parse(sender, body)
        val reminder =
            when {
                parsedReminder != null -> mergeReminder(parsedReminder, extracts)
                result.subCategory == SubCategory.BILL -> reminderFromExtracts(extracts)
                else -> null
            }

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
        )
    }

    private suspend fun persistDerived(
        messageId: Long,
        timestampMs: Long,
        enriched: Enriched,
        skipExisting: Boolean = false,
    ) {
        enriched.transaction?.let { tx ->
            if (!skipExisting || transactionDao.findByRawSmsId(messageId) == null) {
                val accountNumber = tx.accountLast4 ?: ""
                if (accountNumber.isNotEmpty()) {
                    upsertAccount(tx, accountNumber, timestampMs)
                }
                transactionDao.insert(
                    TransactionEntity(
                        amount = tx.amount,
                        type = tx.type,
                        merchantName = tx.merchantName,
                        accountNumber = accountNumber,
                        bankName = tx.bankName.orEmpty(),
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
            if (!skipExisting || reminderDao.findByRawSmsId(messageId) == null) {
                reminderDao.insert(
                    ReminderEntity(
                        type = reminder.type,
                        dueDate = reminder.dueDate?.toEpochMs(),
                        totalDue = reminder.totalDue,
                        minDue = reminder.minDue,
                        accountLast4 = reminder.accountLast4,
                        bankName = reminder.bankName,
                        rawSmsId = messageId,
                        createdAt = timestampMs,
                    ),
                )
            }
        }
    }

    private suspend fun upsertAccount(
        tx: ParsedTransaction,
        accountNumber: String,
        timestampMs: Long,
    ) {
        val bankName = tx.bankName ?: ""
        val existing = accountDao.find(accountNumber, bankName)
        if (existing == null) {
            accountDao.insert(
                AccountEntity(
                    accountNumber = accountNumber,
                    bankName = bankName,
                    type = tx.accountType,
                    lastKnownBalance = tx.balance,
                    lastUpdated = timestampMs,
                ),
            )
        } else if (timestampMs >= existing.lastUpdated) {
            accountDao.update(
                existing.copy(
                    lastKnownBalance = tx.balance ?: existing.lastKnownBalance,
                    lastUpdated = timestampMs,
                ),
            )
        }
    }

    private suspend fun definitions(source: String): List<RuleDefinition> = ruleDao.getBySource(source).mapNotNull { it.toDefinition(json) }

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

    private fun reminderFromExtracts(extracts: Map<String, String>): ParsedReminder? {
        val dueDate = extracts["due_date"]?.let { reminderParser.parseDate(it) }
        val totalDue = extracts["total_due"]?.toAmount()
        val minDue = extracts["min_due"]?.toAmount() ?: extracts["amount"]?.toAmount()
        if (dueDate == null && totalDue == null && minDue == null) return null
        return ParsedReminder(
            type = ReminderType.OTHER,
            dueDate = dueDate,
            totalDue = totalDue,
            minDue = minDue,
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
