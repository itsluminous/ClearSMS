package app.clearsms.work

import android.content.Context
import android.util.Log
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import app.clearsms.data.backup.BackupManager
import app.clearsms.data.prefs.SettingsRepository
import app.clearsms.ui.common.BackupFrequency
import app.clearsms.ui.common.UiPrefs
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.flow.first
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Periodic local backup: exports the database as JSON into APP-PRIVATE
 * internal storage ([Context.filesDir]). Nothing is uploaded anywhere.
 *
 * Internal storage is deliberate: `getExternalFilesDir` is readable by any
 * app holding READ_EXTERNAL_STORAGE on Android 6–10 (and over USB/MTP), which
 * would expose every message body and OTP without the reader holding
 * READ_SMS. User-initiated exports still go through SAF to a location the
 * user explicitly picks — that is a deliberate user action.
 *
 * The automatic backup honors the OTP auto-delete policy: OTP messages older
 * than the retention cutoff are excluded, and because the file is rewritten
 * on every run, OTPs removed by [OtpAutoDeleteWorker] age out of the backup
 * instead of being resurrected by a restore.
 *
 * TODO: encrypt the automatic backup at rest. Until then Settings carries a
 *  note that automatic backups are stored unencrypted in app-private storage.
 */
@HiltWorker
class BackupWorker
    @AssistedInject
    constructor(
        @Assisted appContext: Context,
        @Assisted params: WorkerParameters,
        private val backupManager: BackupManager,
        private val uiPrefs: UiPrefs,
        private val settingsRepository: SettingsRepository,
    ) : CoroutineWorker(appContext, params) {
        override suspend fun doWork(): Result {
            deleteLegacyExternalBackup(applicationContext)

            val frequency = uiPrefs.backupFrequency.first()
            val dir = File(applicationContext.filesDir, BACKUP_DIR)
            val target = File(dir, BACKUP_FILE_NAME)
            if (frequency == BackupFrequency.OFF) {
                // The user turned automatic backups off: produce nothing,
                // remove any previous automatic export, and cancel the
                // periodic schedule (defense in depth for schedules enqueued
                // before the setting was read, e.g. at boot).
                target.delete()
                WorkManager.getInstance(applicationContext).cancelUniqueWork(WORK_NAME)
                return Result.success()
            }
            if (frequency == BackupFrequency.WEEKLY &&
                target.exists() &&
                System.currentTimeMillis() - target.lastModified() < WEEKLY_MIN_AGE_MS
            ) {
                return Result.success()
            }

            val otpPolicy = settingsRepository.otpAutoDeletePolicy.first()
            val otpCutoffMs = OtpAutoDeleteWorker.cutoffFor(otpPolicy, System.currentTimeMillis())

            if (!dir.exists() && !dir.mkdirs()) return Result.retry()
            val temp = File(dir, "$BACKUP_FILE_NAME.tmp")
            return try {
                temp.outputStream().use { backupManager.exportTo(it, otpCutoffMs = otpCutoffMs) }
                if (!temp.renameTo(target)) {
                    temp.copyTo(target, overwrite = true)
                    temp.delete()
                }
                Result.success()
            } catch (e: Exception) {
                Log.w(TAG, "Scheduled backup failed", e)
                temp.delete()
                Result.retry()
            }
        }

        /** Removes the plaintext export a previous app version wrote to external storage. */
        private fun deleteLegacyExternalBackup(context: Context) {
            val legacyDir = context.getExternalFilesDir(BACKUP_DIR) ?: return
            File(legacyDir, BACKUP_FILE_NAME).delete()
            File(legacyDir, "$BACKUP_FILE_NAME.tmp").delete()
        }

        companion object {
            const val WORK_NAME = "periodic_backup"
            const val BACKUP_DIR = "backups"
            const val BACKUP_FILE_NAME = "clearsms-backup.json"
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
