package app.clearsms.work

import android.content.Context
import android.util.Log
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import app.clearsms.data.backup.BackupManager
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import java.io.File

/**
 * Periodic local backup: exports the whole database as JSON into the app's
 * external files directory. The file stays on the device under the user's
 * control — nothing is uploaded anywhere.
 */
@HiltWorker
class BackupWorker
    @AssistedInject
    constructor(
        @Assisted appContext: Context,
        @Assisted params: WorkerParameters,
        private val backupManager: BackupManager,
    ) : CoroutineWorker(appContext, params) {
        override suspend fun doWork(): Result {
            val dir = applicationContext.getExternalFilesDir(BACKUP_DIR) ?: return Result.retry()
            val target = File(dir, BACKUP_FILE_NAME)
            val temp = File(dir, "$BACKUP_FILE_NAME.tmp")
            return try {
                temp.outputStream().use { backupManager.exportTo(it) }
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

        companion object {
            const val WORK_NAME = "periodic_backup"
            const val BACKUP_DIR = "backups"
            const val BACKUP_FILE_NAME = "clearsms-backup.json"
            private const val TAG = "BackupWorker"
        }
    }
