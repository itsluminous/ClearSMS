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
    version = 7,
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

    companion object {
        const val NAME = "clearsms.db"
    }
}
