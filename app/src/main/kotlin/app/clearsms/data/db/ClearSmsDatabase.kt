package app.clearsms.data.db

import androidx.room.AutoMigration
import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.room.migration.AutoMigrationSpec
import androidx.sqlite.db.SupportSQLiteDatabase
import app.clearsms.domain.categorizer.MessageCategorizer
import app.clearsms.domain.parser.DeliveryParser
import app.clearsms.domain.parser.ReminderParser
import app.clearsms.domain.parser.SenderNameResolver
import java.time.Instant
import java.time.ZoneId

/** Room database holding messages, finance data and categorization rules. */
@Database(
    entities = [
        MessageEntity::class,
        MessageFtsEntity::class,
        AccountEntity::class,
        TransactionEntity::class,
        RuleEntity::class,
        ReminderEntity::class,
    ],
    version = 9,
    exportSchema = true,
    autoMigrations = [
        // v1 -> v2: adds the (threadId, timestamp) index for paged queries.
        AutoMigration(from = 1, to = 2),
        // v2 -> v3: adds rules.enabled (default 1) so disabling a rule flips
        // a flag instead of deleting and re-inserting the row.
        AutoMigration(from = 2, to = 3),
        // v3 -> v4: adds reminders.label (delivery tracking reference) and
        // clears reminder rows violating the new "must have a due date"
        // invariant — see [ClearSmsDatabase.DeleteUndatedReminders].
        AutoMigration(from = 3, to = 4, spec = ClearSmsDatabase.DeleteUndatedReminders::class),
        // v4 -> v5: no schema change; re-derives every reminder (amounts,
        // labels, corrected types, stronger settled-payment guard) and
        // consolidates duplicate / nameless finance accounts — see
        // [ClearSmsDatabase.RebuildDerivedData].
        AutoMigration(from = 4, to = 5, spec = ClearSmsDatabase.RebuildDerivedData::class),
        // v5 -> v6: adds the messages_fts search index (external-content
        // FTS4 over sender+body) and back-fills it from the existing rows —
        // see [ClearSmsDatabase.PopulateMessageFts].
        AutoMigration(from = 5, to = 6, spec = ClearSmsDatabase.PopulateMessageFts::class),
        // v6 -> v7: adds messages.isOutgoing (default 0 = incoming) and
        // messages.deliveryStatus, then reconciles existing rows against the
        // system provider's sent box — see [BackfillMessageDirections]
        // (provided at build time because it reads the SMS provider).
        AutoMigration(from = 6, to = 7, spec = BackfillMessageDirections::class),
        // v7 -> v8: adds accounts.availableLimit (nullable) — the issuer-
        // reported available credit limit, kept apart from lastKnownBalance
        // because a card's headroom is not a balance. Existing rows keep
        // their data and start with NULL until the next card SMS arrives.
        AutoMigration(from = 7, to = 8),
        // v8 -> v9: adds transactions.accountId (nullable) — an explicit
        // link to the owning account row, resolved at ingestion by
        // (canonical bank, last-4) instead of re-matching string fields at
        // read time. The spec backfills it, leaving it NULL when no
        // confident owner exists, and cleans up nameless (blank-bank)
        // account rows — see [ClearSmsDatabase.LinkTransactionsToAccounts].
        AutoMigration(from = 8, to = 9, spec = ClearSmsDatabase.LinkTransactionsToAccounts::class),
    ],
)
@TypeConverters(Converters::class)
abstract class ClearSmsDatabase : RoomDatabase() {
    abstract fun messageDao(): MessageDao

    abstract fun accountDao(): AccountDao

    abstract fun transactionDao(): TransactionDao

    abstract fun ruleDao(): RuleDao

    abstract fun reminderDao(): ReminderDao

    /**
     * The auto migration creates the empty `messages_fts` virtual table;
     * this back-fills it from the existing `messages` rows so search finds
     * pre-upgrade history. Room's generated sync triggers keep it current
     * from then on.
     */
    class PopulateMessageFts : AutoMigrationSpec {
        override fun onPostMigrate(db: SupportSQLiteDatabase) {
            db.execSQL("INSERT INTO messages_fts(messages_fts) VALUES('rebuild')")
        }
    }

    /**
     * Reminders without a due date are not actionable (they were the junk in
     * the Alerts "Others" filter) and can no longer be produced by the
     * parser. This deletes the invalid rows already on the device; valid
     * dated reminders are untouched, and a recategorization rebuilds any
     * reminder the tightened parser still stands behind.
     */
    class DeleteUndatedReminders : AutoMigrationSpec {
        override fun onPostMigrate(db: SupportSQLiteDatabase) {
            db.execSQL("DELETE FROM reminders WHERE dueDate IS NULL")
        }
    }

    /**
     * One-time repair of derived data for rows created by older parsers:
     *
     * 1. Reminders are DROPPED and re-derived from the stored messages, so
     *    existing rows gain the newly-extracted amounts and labels, the
     *    RD-installment-as-EMI mis-typing is corrected, and reminders the
     *    strengthened settled-payment guard now rejects (thank-you-for-
     *    payment texts) disappear.
     * 2. Account bank names are canonicalized ("SBI" / "State Bank of India"
     *    become one), blank names are resolved from the account's own
     *    transaction senders/bodies, and duplicate rows (same last-4 + same
     *    canonical bank) are merged into the newest row.
     * 3. Transaction bank names get the same treatment so every transaction
     *    keeps pointing at its surviving account row.
     */
    class RebuildDerivedData : AutoMigrationSpec {
        private val reminderParser = ReminderParser()
        private val deliveryParser = DeliveryParser()

        override fun onPostMigrate(db: SupportSQLiteDatabase) {
            rebuildReminders(db)
            // Accounts first: blank-bank rows are resolved from their still-
            // blank transactions, which the transaction pass then rewrites.
            consolidateAccounts(db)
            canonicalizeTransactions(db)
        }

        private fun rebuildReminders(db: SupportSQLiteDatabase) {
            db.execSQL("DELETE FROM reminders")
            db.query("SELECT id, sender, body, timestamp, subCategory FROM messages").use { cursor ->
                while (cursor.moveToNext()) {
                    val messageId = cursor.getLong(0)
                    val sender = cursor.getString(1)
                    val body = cursor.getString(2).take(MessageCategorizer.MAX_EVAL_BODY_LENGTH)
                    val timestamp = cursor.getLong(3)
                    val subCategory = if (cursor.isNull(4)) null else cursor.getString(4)
                    if (subCategory == "DELIVERY") {
                        val delivery = deliveryParser.parse(sender, body) ?: continue
                        val messageDate =
                            Instant
                                .ofEpochMilli(timestamp)
                                .atZone(ZoneId.systemDefault())
                                .toLocalDate()
                        insertReminder(
                            db,
                            type = "DELIVERY",
                            dueDate = delivery.expectedDate(messageDate).toEpochMs(),
                            totalDue = null,
                            minDue = null,
                            accountLast4 = null,
                            bankName = delivery.merchant,
                            label = delivery.reference,
                            rawSmsId = messageId,
                            createdAt = timestamp,
                        )
                    } else {
                        val reminder = reminderParser.parse(sender, body) ?: continue
                        insertReminder(
                            db,
                            type = reminder.type.name,
                            dueDate = reminder.dueDate?.toEpochMs(),
                            totalDue = reminder.totalDue,
                            minDue = reminder.minDue,
                            accountLast4 = reminder.accountLast4,
                            bankName = reminder.bankName,
                            label = reminder.label,
                            rawSmsId = messageId,
                            createdAt = timestamp,
                        )
                    }
                }
            }
        }

        @Suppress("LongParameterList")
        private fun insertReminder(
            db: SupportSQLiteDatabase,
            type: String,
            dueDate: Long?,
            totalDue: Double?,
            minDue: Double?,
            accountLast4: String?,
            bankName: String?,
            label: String?,
            rawSmsId: Long,
            createdAt: Long,
        ) {
            db.execSQL(
                """
                INSERT INTO reminders (type, dueDate, totalDue, minDue, accountLast4, bankName, label, rawSmsId, createdAt)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                """.trimIndent(),
                arrayOf(type, dueDate, totalDue, minDue, accountLast4, bankName, label, rawSmsId, createdAt),
            )
        }

        /** Canonical (or sender-resolved) bank name for every transaction row. */
        private fun canonicalizeTransactions(db: SupportSQLiteDatabase) {
            val updates = mutableListOf<Pair<Long, String>>()
            db
                .query(
                    """
                    SELECT t.id, t.bankName, m.sender, m.body
                    FROM transactions t LEFT JOIN messages m ON m.id = t.rawSmsId
                    """.trimIndent(),
                ).use { cursor ->
                    while (cursor.moveToNext()) {
                        val id = cursor.getLong(0)
                        val stored = cursor.getString(1)
                        val sender = if (cursor.isNull(2)) null else cursor.getString(2)
                        val body = if (cursor.isNull(3)) "" else cursor.getString(3)
                        val resolved =
                            if (stored.isBlank()) {
                                sender?.let {
                                    SenderNameResolver.bankNameFor(it, body.take(MessageCategorizer.MAX_EVAL_BODY_LENGTH))
                                }
                            } else {
                                SenderNameResolver.canonicalize(stored)
                            }
                        if (resolved != null && resolved != stored) updates += id to resolved
                    }
                }
            for ((id, bank) in updates) {
                db.execSQL("UPDATE transactions SET bankName = ? WHERE id = ?", arrayOf(bank, id))
            }
        }

        private class AccountRow(
            val id: Long,
            val accountNumber: String,
            val bankName: String,
            val balance: Double?,
            val creditLimit: Double?,
            val lastUpdated: Long,
        )

        private fun consolidateAccounts(db: SupportSQLiteDatabase) {
            val rows = mutableListOf<AccountRow>()
            db
                .query("SELECT id, accountNumber, bankName, lastKnownBalance, creditLimit, lastUpdated FROM accounts")
                .use { cursor ->
                    while (cursor.moveToNext()) {
                        rows +=
                            AccountRow(
                                id = cursor.getLong(0),
                                accountNumber = cursor.getString(1),
                                bankName = cursor.getString(2),
                                balance = if (cursor.isNull(3)) null else cursor.getDouble(3),
                                creditLimit = if (cursor.isNull(4)) null else cursor.getDouble(4),
                                lastUpdated = cursor.getLong(5),
                            )
                    }
                }
            // Blank names resolve from the account's own transactions (the
            // rows written before bank resolution existed carry bankName='').
            val resolvedName =
                rows.associate { row ->
                    val name =
                        if (row.bankName.isBlank()) {
                            resolveFromTransactions(db, row.accountNumber) ?: ""
                        } else {
                            SenderNameResolver.canonicalize(row.bankName).orEmpty()
                        }
                    row.id to name
                }
            val groups = rows.groupBy { it.accountNumber to resolvedName.getValue(it.id) }
            val deletions = mutableListOf<Long>()
            val survivors = mutableListOf<Triple<AccountRow, String, Pair<Double?, Double?>>>()
            for ((key, group) in groups) {
                val (_, bank) = key
                val newestFirst = group.sortedByDescending { it.lastUpdated }
                val survivor = newestFirst.first()
                deletions += newestFirst.drop(1).map { it.id }
                val balance = newestFirst.firstNotNullOfOrNull { it.balance }
                val creditLimit = newestFirst.firstNotNullOfOrNull { it.creditLimit }
                if (newestFirst.size > 1 || bank != survivor.bankName) {
                    survivors += Triple(survivor, bank, balance to creditLimit)
                }
            }
            // Losers first so a renamed survivor never trips the unique
            // (accountNumber, bankName) index against a row it absorbs.
            for (id in deletions) {
                db.execSQL("DELETE FROM accounts WHERE id = ?", arrayOf(id))
            }
            for ((survivor, bank, merged) in survivors) {
                val (balance, creditLimit) = merged
                db.execSQL(
                    "UPDATE accounts SET bankName = ?, lastKnownBalance = ?, creditLimit = ? WHERE id = ?",
                    arrayOf(bank, balance, creditLimit, survivor.id),
                )
            }
        }

        /**
         * Majority institution over the senders/bodies of the account's
         * unresolved (blank-bank) transactions; null when the account has
         * none — such a row is left untouched rather than merged blindly.
         */
        private fun resolveFromTransactions(
            db: SupportSQLiteDatabase,
            accountNumber: String,
        ): String? {
            val votes = HashMap<String, Int>()
            db
                .query(
                    """
                    SELECT m.sender, m.body FROM transactions t
                    INNER JOIN messages m ON m.id = t.rawSmsId
                    WHERE t.accountNumber = ? AND t.bankName = ''
                    """.trimIndent(),
                    arrayOf(accountNumber),
                ).use { cursor ->
                    while (cursor.moveToNext()) {
                        val sender = cursor.getString(0)
                        val body = cursor.getString(1).take(MessageCategorizer.MAX_EVAL_BODY_LENGTH)
                        val name = SenderNameResolver.bankNameFor(sender, body) ?: continue
                        votes[name] = (votes[name] ?: 0) + 1
                    }
                }
            return votes.maxByOrNull { it.value }?.key
        }

        private fun java.time.LocalDate.toEpochMs(): Long = atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
    }

    /**
     * One-time repair for the "shared last-4" bugs (a tail like 8709 can
     * legitimately exist at several banks):
     *
     * 1. Blank-bank account rows (created by an ingestion path that
     *    blanked an implausible issuer but still inserted the account) are
     *    merged into the named account with the same last-4 AND type when
     *    exactly one exists, otherwise deleted. Their transactions are
     *    re-pointed by the backfill below; user notes live on transaction
     *    rows and are untouched.
     * 2. Every transaction gains an [TransactionEntity.accountId] owner:
     *    matched by (last-4, canonical bank); a transaction with a blank
     *    bank attaches only when exactly ONE named bank holds that tail.
     *    Anything ambiguous stays NULL — read paths then fall back to the
     *    exact (accountNumber, bankName) pair, never the number alone.
     */
    class LinkTransactionsToAccounts : AutoMigrationSpec {
        override fun onPostMigrate(db: SupportSQLiteDatabase) {
            mergeOrDeleteBlankAccounts(db)
            backfillAccountIds(db)
        }

        private class Row(
            val id: Long,
            val accountNumber: String,
            val bankName: String,
            val type: String,
            val balance: Double?,
            val availableLimit: Double?,
            val creditLimit: Double?,
            val lastUpdated: Long,
        )

        private fun loadAccounts(db: SupportSQLiteDatabase): List<Row> {
            val rows = mutableListOf<Row>()
            db
                .query(
                    "SELECT id, accountNumber, bankName, type, lastKnownBalance, availableLimit, creditLimit, lastUpdated FROM accounts",
                ).use { cursor ->
                    while (cursor.moveToNext()) {
                        rows +=
                            Row(
                                id = cursor.getLong(0),
                                accountNumber = cursor.getString(1),
                                bankName = cursor.getString(2),
                                type = cursor.getString(3),
                                balance = if (cursor.isNull(4)) null else cursor.getDouble(4),
                                availableLimit = if (cursor.isNull(5)) null else cursor.getDouble(5),
                                creditLimit = if (cursor.isNull(6)) null else cursor.getDouble(6),
                                lastUpdated = cursor.getLong(7),
                            )
                    }
                }
            return rows
        }

        private fun mergeOrDeleteBlankAccounts(db: SupportSQLiteDatabase) {
            val accounts = loadAccounts(db)
            val named = accounts.filter { it.bankName.isNotBlank() }
            for (blank in accounts.filter { it.bankName.isBlank() }) {
                val candidates =
                    named.filter { it.accountNumber == blank.accountNumber && it.type == blank.type }
                val survivor = candidates.singleOrNull()
                if (survivor != null) {
                    // Merge: the named row keeps its own figures; the blank
                    // row only ever fills gaps, and only when it is newer.
                    val blankNewer = blank.lastUpdated > survivor.lastUpdated
                    db.execSQL(
                        "UPDATE accounts SET lastKnownBalance = ?, availableLimit = ?, creditLimit = ?, lastUpdated = ? WHERE id = ?",
                        arrayOf(
                            if (blankNewer) blank.balance ?: survivor.balance else survivor.balance,
                            if (blankNewer) blank.availableLimit ?: survivor.availableLimit else survivor.availableLimit,
                            if (blankNewer) blank.creditLimit ?: survivor.creditLimit else survivor.creditLimit,
                            maxOf(blank.lastUpdated, survivor.lastUpdated),
                            survivor.id,
                        ),
                    )
                }
                db.execSQL("DELETE FROM accounts WHERE id = ?", arrayOf(blank.id))
            }
        }

        private fun backfillAccountIds(db: SupportSQLiteDatabase) {
            val accounts = loadAccounts(db).filter { it.bankName.isNotBlank() }
            // Exact (accountNumber, bankName) matches — set-based, covers
            // the overwhelming majority of rows.
            for (account in accounts) {
                db.execSQL(
                    "UPDATE transactions SET accountId = ? WHERE accountNumber = ? AND bankName = ?",
                    arrayOf(account.id, account.accountNumber, account.bankName),
                )
            }
            // Remaining rows: canonicalization variants and blank-bank
            // transactions, resolved per distinct (accountNumber, bankName)
            // pair. Groups keyed by canonical bank so "SBI" finds the row
            // stored as "State Bank of India".
            val byKey = accounts.groupBy { it.accountNumber to SenderNameResolver.canonicalize(it.bankName) }
            val byTail = accounts.groupBy { it.accountNumber }
            val pairs = mutableListOf<Pair<String, String>>()
            db
                .query(
                    "SELECT DISTINCT accountNumber, bankName FROM transactions WHERE accountId IS NULL AND accountNumber != ''",
                ).use { cursor ->
                    while (cursor.moveToNext()) {
                        pairs += cursor.getString(0) to cursor.getString(1)
                    }
                }
            for ((tail, bank) in pairs) {
                val owner =
                    if (bank.isNotBlank()) {
                        byKey[tail to SenderNameResolver.canonicalize(bank)]?.singleOrNull()
                    } else {
                        // Bank unknown on the row: attach only when exactly
                        // ONE named bank holds this tail.
                        byTail[tail]
                            ?.takeIf { rows -> rows.map { it.bankName }.distinct().size == 1 }
                            ?.singleOrNull()
                    }
                if (owner != null) {
                    db.execSQL(
                        "UPDATE transactions SET accountId = ? WHERE accountId IS NULL AND accountNumber = ? AND bankName = ?",
                        arrayOf(owner.id, tail, bank),
                    )
                }
            }
        }
    }

    companion object {
        const val NAME = "clearsms.db"
    }
}
