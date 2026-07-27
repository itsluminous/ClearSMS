package app.clearsms.work

import android.content.Context
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import app.clearsms.R
import app.clearsms.notification.Channels
import app.clearsms.sms.SystemSmsImporter
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject

/**
 * One-time import of the existing message history from the system SMS
 * provider, run right after Clear SMS becomes the default app.
 *
 * Progress is exposed both through WorkManager's progress [Data] (observed by
 * the onboarding UI) and a low-importance progress notification. A foreground
 * service is intentionally not used: targeting API 35 that would require a
 * declared service type, and the import is short-lived batch work that
 * survives fine as regular expedited-eligible work.
 */
@HiltWorker
class InitialSyncWorker
    @AssistedInject
    constructor(
        @Assisted appContext: Context,
        @Assisted params: WorkerParameters,
        private val systemSmsImporter: SystemSmsImporter,
    ) : CoroutineWorker(appContext, params) {
        override suspend fun doWork(): Result {
            Channels.ensureCreated(applicationContext)
            val manager = NotificationManagerCompat.from(applicationContext)
            var lastPublished = -1
            try {
                systemSmsImporter.importAll { imported, total ->
                    setProgress(
                        Data
                            .Builder()
                            .putInt(PROGRESS_IMPORTED, imported)
                            .putInt(PROGRESS_TOTAL, total)
                            .build(),
                    )
                    // Update the notification at most once per percent.
                    val percent = if (total == 0) 100 else imported * 100 / total
                    if (percent != lastPublished) {
                        lastPublished = percent
                        postProgressNotification(manager, imported, total)
                    }
                }
            } finally {
                manager.cancel(SYNC_NOTIFICATION_ID)
            }
            return Result.success()
        }

        private fun postProgressNotification(
            manager: NotificationManagerCompat,
            imported: Int,
            total: Int,
        ) {
            val notification =
                NotificationCompat
                    .Builder(applicationContext, Channels.SYNC)
                    .setSmallIcon(R.drawable.ic_notification)
                    .setContentTitle(applicationContext.getString(R.string.sync_title))
                    .setContentText(applicationContext.getString(R.string.sync_progress, imported, total))
                    .setProgress(total, imported, total == 0)
                    .setOngoing(true)
                    .setOnlyAlertOnce(true)
                    .setCategory(NotificationCompat.CATEGORY_PROGRESS)
                    .build()
            try {
                manager.notify(SYNC_NOTIFICATION_ID, notification)
            } catch (_: SecurityException) {
                // POST_NOTIFICATIONS not granted; progress is still visible in-app.
            }
        }

        companion object {
            const val WORK_NAME = "initial_sync"
            const val PROGRESS_IMPORTED = "imported"
            const val PROGRESS_TOTAL = "total"
            private const val SYNC_NOTIFICATION_ID = 70_001

            /** Enqueues the one-time import; an already running import is kept. */
            fun enqueue(context: Context) {
                WorkManager
                    .getInstance(context)
                    .enqueueUniqueWork(
                        WORK_NAME,
                        ExistingWorkPolicy.KEEP,
                        OneTimeWorkRequestBuilder<InitialSyncWorker>().build(),
                    )
            }
        }
    }
