package app.clearsms.work

import android.content.Context
import android.util.Log
import androidx.core.net.toUri
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import app.clearsms.data.backup.BackupManager
import app.clearsms.data.backup.SettingsBackupManager
import app.clearsms.data.prefs.SettingsRepository
import app.clearsms.ui.common.BackupFrequency
import app.clearsms.ui.common.UiPrefs
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.flow.first
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Periodic local backup into the directory the user chose via SAF when
 * enabling the backup frequency (a persisted `ACTION_OPEN_DOCUMENT_TREE`
 * grant). Each run writes BOTH exports under stable names, overwriting the
 * previous copies:
 *
 * - [MESSAGES_FILE_NAME] — the full database export ([BackupManager])
 * - [SETTINGS_FILE_NAME] — the settings export ([SettingsBackupManager])
 *
 * Nothing is uploaded anywhere. The frequency setting cannot reach
 * DAILY/WEEKLY without a granted directory (Settings gates it), so a missing
 * uri here means the grant was lost: the run fails and raises the
 * directory-error flag that Settings surfaces as a "choose the folder again"
 * warning — chosen over a notification because the fix lives in Settings,
 * needs no POST_NOTIFICATIONS permission, and stays visible until resolved
 * instead of being swiped away.
 *
 * The automatic backup honors the OTP auto-delete policy: OTP messages older
 * than the retention cutoff are excluded, and because the file is rewritten
 * on every run, OTPs removed by [OtpAutoDeleteWorker] age out of the backup
 * instead of being resurrected by a restore.
 */
@HiltWorker
class BackupWorker
    @AssistedInject
    constructor(
        @Assisted appContext: Context,
        @Assisted params: WorkerParameters,
        private val backupManager: BackupManager,
        private val settingsBackupManager: SettingsBackupManager,
        private val uiPrefs: UiPrefs,
        private val settingsRepository: SettingsRepository,
        private val documentStoreFactory: BackupDocumentStore.Factory,
    ) : CoroutineWorker(appContext, params) {
        override suspend fun doWork(): Result {
            deleteLegacyLocalBackups(applicationContext)

            val frequency = uiPrefs.backupFrequency.first()
            if (frequency == BackupFrequency.OFF) {
                // The user turned automatic backups off: produce nothing and
                // cancel the periodic schedule (defense in depth for schedules
                // enqueued before the setting was read, e.g. at boot).
                WorkManager.getInstance(applicationContext).cancelUniqueWork(WORK_NAME)
                return Result.success()
            }
            val now = System.currentTimeMillis()
            if (frequency == BackupFrequency.WEEKLY &&
                now - uiPrefs.lastAutoBackupMs.first() < WEEKLY_MIN_AGE_MS
            ) {
                return Result.success()
            }

            val treeUri = uiPrefs.backupDirectoryUri.first()?.toUri()
            if (treeUri == null) {
                // Settings never lets DAILY/WEEKLY activate without a granted
                // directory, so reaching here means the grant was lost.
                uiPrefs.setBackupDirectoryError(true)
                return Result.failure()
            }
            val store = documentStoreFactory.create(treeUri)

            val otpPolicy = settingsRepository.otpAutoDeletePolicy.first()
            val otpCutoffMs = OtpAutoDeleteWorker.cutoffFor(otpPolicy, now)

            return try {
                val messagesOut = store.openForWrite(MESSAGES_FILE_NAME)
                val settingsOut = messagesOut?.let { store.openForWrite(SETTINGS_FILE_NAME) }
                if (messagesOut == null || settingsOut == null) {
                    messagesOut?.close()
                    // Directory deleted or permission revoked: fail (don't
                    // retry into the same wall) and surface the fix in Settings.
                    uiPrefs.setBackupDirectoryError(true)
                    return Result.failure()
                }
                messagesOut.use { backupManager.exportTo(it, otpCutoffMs = otpCutoffMs) }
                settingsOut.use { settingsBackupManager.exportTo(it) }
                uiPrefs.setBackupDirectoryError(false)
                uiPrefs.setLastAutoBackupMs(now)
                Result.success()
            } catch (e: Exception) {
                // Transient I/O trouble (storage full, provider hiccup):
                // worth retrying, unlike a lost grant.
                Log.w(TAG, "Scheduled backup failed", e)
                Result.retry()
            }
        }

        /**
         * Removes the exports previous app versions wrote to app-private
         * internal and external storage — automatic backups now live only in
         * the user-chosen directory.
         */
        private fun deleteLegacyLocalBackups(context: Context) {
            val internalDir = File(context.filesDir, LEGACY_BACKUP_DIR)
            File(internalDir, LEGACY_BACKUP_FILE_NAME).delete()
            File(internalDir, "$LEGACY_BACKUP_FILE_NAME.tmp").delete()
            context.getExternalFilesDir(LEGACY_BACKUP_DIR)?.let { legacyDir ->
                File(legacyDir, LEGACY_BACKUP_FILE_NAME).delete()
                File(legacyDir, "$LEGACY_BACKUP_FILE_NAME.tmp").delete()
            }
        }

        companion object {
            const val WORK_NAME = "periodic_backup"
            const val MESSAGES_FILE_NAME = "clearsms-backup-messages.json"
            const val SETTINGS_FILE_NAME = "clearsms-backup-settings.json"
            const val LEGACY_BACKUP_DIR = "backups"
            const val LEGACY_BACKUP_FILE_NAME = "clearsms-backup.json"
            private const val TAG = "BackupWorker"

            /** WEEKLY runs skip the export while the last one is younger than ~6.5 days. */
            private val WEEKLY_MIN_AGE_MS = TimeUnit.HOURS.toMillis(6 * 24 + 12)

            /**
             * Applies the user's backup frequency to the WorkManager schedule:
             * OFF cancels the periodic work outright; DAILY/WEEKLY (re-)enqueue
             * the daily check (WEEKLY throttles inside [doWork]).
             */
            fun applyFrequency(
                context: Context,
                frequency: BackupFrequency,
            ) {
                val workManager = WorkManager.getInstance(context)
                when (frequency) {
                    BackupFrequency.OFF -> workManager.cancelUniqueWork(WORK_NAME)
                    BackupFrequency.DAILY, BackupFrequency.WEEKLY ->
                        workManager.enqueueUniquePeriodicWork(
                            WORK_NAME,
                            ExistingPeriodicWorkPolicy.UPDATE,
                            PeriodicWorkRequestBuilder<BackupWorker>(24, TimeUnit.HOURS).build(),
                        )
                }
            }
        }
    }
