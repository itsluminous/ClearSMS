package app.clearsms.data.db

import androidx.room.AutoMigration
import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.room.migration.AutoMigrationSpec
import androidx.sqlite.db.SupportSQLiteDatabase

/** Room database holding messages, finance data and categorization rules. */
@Database(
    entities = [
        MessageEntity::class,
        AccountEntity::class,
        TransactionEntity::class,
        RuleEntity::class,
        ReminderEntity::class,
    ],
    version = 4,
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

    companion object {
        const val NAME = "clearsms.db"
    }
}
