package app.clearsms.data.backup

import androidx.room.withTransaction
import app.clearsms.data.db.ClearSmsDatabase
import app.clearsms.data.rules.RuleSources
import app.clearsms.domain.model.Category
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.decodeFromStream
import kotlinx.serialization.json.encodeToStream
import java.io.InputStream
import java.io.OutputStream

/** Outcome of a successful restore, surfaced to the user. */
data class RestoreResult(
    val messages: Int,
    val accounts: Int,
    val transactions: Int,
    val rules: Int,
    val reminders: Int,
    /** Unknown field values replaced with safe defaults (e.g. an unknown category). */
    val defaultedValues: Int,
    /** Rows dropped because no safe default existed (e.g. an unknown transaction type). */
    val skippedRows: Int,
)

/**
 * Local backup and restore of the whole database as a single JSON document.
 *
 * Backups are plain files the user controls; nothing ever leaves the device.
 */
class BackupManager(
    private val database: ClearSmsDatabase,
    private val json: Json,
) {
    /**
     * Serializes all tables to [output] as JSON. The stream is not closed.
     *
     * Only USER rules are exported: bundled/community rules ship with the APK
     * and re-seeding them from a backup would let a crafted file overwrite
     * them (their ids are public in the repository).
     *
     * @param otpCutoffMs when set, OTP messages older than this timestamp are
     * excluded - automatic backups pass the user's OTP auto-delete cutoff so
     * a backup can never resurrect OTPs the retention policy already deleted.
     */
    @OptIn(ExperimentalSerializationApi::class)
    suspend fun exportTo(
        output: OutputStream,
        otpCutoffMs: Long? = null,
    ) {
        val messages =
            database.messageDao().getAll().filter { message ->
                // Deleted messages never travel: neither rows inside the
                // transient undo window nor recycle-bin residents belong in
                // a backup - restoring one must not resurrect deletions.
                message.deletedAt == null &&
                    (otpCutoffMs == null || message.category != Category.OTP || message.timestamp >= otpCutoffMs)
            }
        val document =
            BackupDocument(
                createdAt = System.currentTimeMillis(),
                messages = messages.map { it.toBackup() },
                accounts = database.accountDao().getAll().map { it.toBackup() },
                transactions = database.transactionDao().getAll().map { it.toBackup() },
                rules = database.ruleDao().getBySource(RuleSources.USER).map { it.toBackup() },
                reminders = database.reminderDao().getAll().map { it.toBackup() },
                pins = database.threadPinDao().getAll().map { it.toBackup() },
            )
        json.encodeToStream(BackupDocument.serializer(), document, output)
    }

    /**
     * Replaces the database contents with the backup read from [input].
     *
     * Safety properties:
     * - the ENTIRE document is decoded and mapped to entities BEFORE any
     *   mutation, so a malformed file leaves the database untouched;
     * - unknown enum values never throw - they are defaulted or their row is
     *   skipped, tallied in the returned [RestoreResult];
     * - delete + insert run in one transaction, so a mid-restore failure
     *   rolls back to the pre-restore state;
     * - bundled/community rules are never replaced from the file; restored
     *   rules are forced to user source with namespaced ids.
     *
     * @throws IllegalArgumentException when the stream is not a valid backup
     * or was created by a newer app version.
     */
    @OptIn(ExperimentalSerializationApi::class)
    suspend fun importFrom(input: InputStream): RestoreResult {
        val document =
            try {
                json.decodeFromStream(BackupDocument.serializer(), input)
            } catch (e: Exception) {
                throw IllegalArgumentException("Not a valid backup file", e)
            }
        if (document.formatVersion > BackupDocument.FORMAT_VERSION) {
            throw IllegalArgumentException(
                "Backup format ${document.formatVersion} is newer than this app supports " +
                    "(up to ${BackupDocument.FORMAT_VERSION}); update the app before restoring",
            )
        }
        require(document.formatVersion >= 1) {
            "Not a valid backup file (format ${document.formatVersion})"
        }
        // Format 1 is the only released format so far; when FORMAT_VERSION is
        // bumped, migrate older documents here before mapping.

        // Map everything up front - validation failures happen with the
        // database untouched.
        val issues = RestoreIssues()
        val messages = document.messages.map { it.toEntity(issues) }
        val accounts = document.accounts.map { it.toEntity(issues) }
        val transactions = document.transactions.mapNotNull { it.toEntityOrNull(issues) }
        val rules =
            document.rules
                .filter { it.source == RuleSources.USER }
                .map { it.toUserEntity() }
        val reminders = document.reminders.map { it.toEntity(issues) }
        val pins = document.pins.map { it.toEntity() }

        database.withTransaction {
            database.messageDao().deleteAll()
            database.accountDao().deleteAll()
            database.transactionDao().deleteAll()
            // Bundled/community rules stay: only the user's own rules are replaced.
            database.ruleDao().deleteBySource(RuleSources.USER)
            database.reminderDao().deleteAll()
            database.threadPinDao().deleteAll()

            database.messageDao().insertAll(messages)
            database.accountDao().insertAll(accounts)
            database.transactionDao().insertAll(transactions)
            database.ruleDao().insertAll(rules)
            database.reminderDao().insertAll(reminders)
            // Pins are keyed by normalized sender, so they reattach to
            // whatever thread ids the restored messages carry.
            database.threadPinDao().upsertAll(pins)
        }
        return RestoreResult(
            messages = messages.size,
            accounts = accounts.size,
            transactions = transactions.size,
            rules = rules.size,
            reminders = reminders.size,
            defaultedValues = issues.defaultedValues,
            skippedRows = issues.skippedRows,
        )
    }
}
