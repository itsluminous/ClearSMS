package app.clearsms.data.backup

import app.clearsms.data.db.ClearSmsDatabase
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.decodeFromStream
import kotlinx.serialization.json.encodeToStream
import java.io.InputStream
import java.io.OutputStream

/**
 * Local backup and restore of the whole database as a single JSON document.
 *
 * Backups are plain files the user controls; nothing ever leaves the device.
 */
class BackupManager(
    private val database: ClearSmsDatabase,
    private val json: Json,
) {
    /** Serializes all tables to [output] as JSON. The stream is not closed. */
    @OptIn(ExperimentalSerializationApi::class)
    suspend fun exportTo(output: OutputStream) {
        val document =
            BackupDocument(
                createdAt = System.currentTimeMillis(),
                messages = database.messageDao().getAll().map { it.toBackup() },
                accounts = database.accountDao().getAll().map { it.toBackup() },
                transactions = database.transactionDao().getAll().map { it.toBackup() },
                rules = database.ruleDao().getAll().map { it.toBackup() },
                reminders = database.reminderDao().getAll().map { it.toBackup() },
            )
        json.encodeToStream(BackupDocument.serializer(), document, output)
    }

    /**
     * Replaces the database contents with the backup read from [input].
     *
     * @throws IllegalArgumentException when the stream is not a valid backup.
     */
    @OptIn(ExperimentalSerializationApi::class)
    suspend fun importFrom(input: InputStream) {
        val document =
            try {
                json.decodeFromStream(BackupDocument.serializer(), input)
            } catch (e: Exception) {
                throw IllegalArgumentException("Not a valid backup file", e)
            }
        database.messageDao().deleteAll()
        database.accountDao().deleteAll()
        database.transactionDao().deleteAll()
        database.ruleDao().deleteAll()
        database.reminderDao().deleteAll()

        database.messageDao().insertAll(document.messages.map { it.toEntity() })
        database.accountDao().insertAll(document.accounts.map { it.toEntity() })
        database.transactionDao().insertAll(document.transactions.map { it.toEntity() })
        database.ruleDao().insertAll(document.rules.map { it.toEntity() })
        database.reminderDao().insertAll(document.reminders.map { it.toEntity() })
    }
}
