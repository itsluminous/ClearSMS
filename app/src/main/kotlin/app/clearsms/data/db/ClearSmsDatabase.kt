package app.clearsms.data.db

import androidx.room.AutoMigration
import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters

/** Room database holding messages, finance data and categorization rules. */
@Database(
    entities = [
        MessageEntity::class,
        AccountEntity::class,
        TransactionEntity::class,
        RuleEntity::class,
        ReminderEntity::class,
    ],
    version = 2,
    exportSchema = true,
    autoMigrations = [
        // v1 -> v2: adds the (threadId, timestamp) index for paged queries.
        AutoMigration(from = 1, to = 2),
    ],
)
@TypeConverters(Converters::class)
abstract class ClearSmsDatabase : RoomDatabase() {
    abstract fun messageDao(): MessageDao

    abstract fun accountDao(): AccountDao

    abstract fun transactionDao(): TransactionDao

    abstract fun ruleDao(): RuleDao

    abstract fun reminderDao(): ReminderDao

    companion object {
        const val NAME = "clearsms.db"
    }
}
