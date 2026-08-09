package app.clearsms.data.backup

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * The single source of backup file names, all carrying a `yyyyMMddHHmm`
 * timestamp suffix (e.g. `clearsms-settings-202608091011.json`) so
 * successive backups never overwrite each other and sort chronologically
 * by plain name ordering.
 *
 * Manual exports and the automatic worker share this object; the worker
 * additionally prunes old automatic backups per prefix (see
 * [app.clearsms.work.BackupWorker]).
 */
object BackupFileNames {
    /** Manual Settings → "Back up messages" export. */
    const val MANUAL_MESSAGES_PREFIX = "clearsms-backup"

    /** Manual Settings → "Back up settings" export. */
    const val MANUAL_SETTINGS_PREFIX = "clearsms-settings"

    /** Automatic worker: full database export. */
    const val AUTO_MESSAGES_PREFIX = "clearsms-backup-messages"

    /** Automatic worker: settings export. */
    const val AUTO_SETTINGS_PREFIX = "clearsms-backup-settings"

    private const val PATTERN = "yyyyMMddHHmm"

    fun timestamp(nowMs: Long): String = SimpleDateFormat(PATTERN, Locale.US).format(Date(nowMs))

    fun manualMessages(nowMs: Long): String = named(MANUAL_MESSAGES_PREFIX, nowMs)

    fun manualSettings(nowMs: Long): String = named(MANUAL_SETTINGS_PREFIX, nowMs)

    fun autoMessages(nowMs: Long): String = named(AUTO_MESSAGES_PREFIX, nowMs)

    fun autoSettings(nowMs: Long): String = named(AUTO_SETTINGS_PREFIX, nowMs)

    /**
     * True when [fileName] is a timestamped backup of the given [prefix].
     * Exact-prefix match: `clearsms-backup-202608091011.json` matches
     * `clearsms-backup` but NOT `clearsms-backup-messages`, because the
     * remainder must be exactly 12 digits + `.json`.
     */
    fun matches(
        prefix: String,
        fileName: String,
    ): Boolean {
        if (!fileName.startsWith("$prefix-") || !fileName.endsWith(".json")) return false
        val stamp = fileName.removePrefix("$prefix-").removeSuffix(".json")
        return stamp.length == PATTERN.length && stamp.all(Char::isDigit)
    }

    private fun named(
        prefix: String,
        nowMs: Long,
    ): String = "$prefix-${timestamp(nowMs)}.json"
}
