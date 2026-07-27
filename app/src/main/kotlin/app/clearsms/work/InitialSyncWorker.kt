package app.clearsms.work

import android.app.Notification
import android.content.Context
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.hilt.work.HiltWorker
import androidx.work.BackoffPolicy
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.OneTimeWorkRequest
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import app.clearsms.R
import app.clearsms.notification.Channels
import app.clearsms.sms.SystemSmsImporter
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import java.util.concurrent.TimeUnit

/**
 * One-time import of the existing message history from the system SMS
 * provider, run right after Clear SMS becomes the default app.
 *
 * Durability: the request is expedited (falling back to regular work when the
 * quota is exhausted) so the import keeps running with the screen off; on
 * devices below Android 12 WorkManager promotes it to a foreground service
 * using [getForegroundInfo]. If the system still stops or kills the run, it
 * is retried with backoff and resumes from [SystemSmsImporter]'s durable
 * checkpoint instead of starting over, and the unique `systemSmsId` index
 * makes any overlap harmless.
 *
 * Progress is exposed through WorkManager's progress [Data] (observed by the
 * onboarding UI, which never owns the import) and a low-importance progress
 * notification, both updated once per committed page.
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
            return try {
                systemSmsImporter.importAll { imported, total ->
                    setProgress(
                        Data
                            .Builder()
                            .putInt(PROGRESS_IMPORTED, imported)
                            .putInt(PROGRESS_TOTAL, total)
                            .build(),
                    )
                    postProgressNotification(manager, imported, total)
                }
                Result.success()
            } catch (e: Exception) {
                Log.w(TAG, "Import attempt $runAttemptCount failed; will resume from checkpoint", e)
                if (runAttemptCount < MAX_ATTEMPTS) Result.retry() else Result.failure()
            } finally {
                manager.cancel(SYNC_NOTIFICATION_ID)
            }
        }

        /**
         * Used by WorkManager when it runs this expedited job as a foreground
         * service (Android 11 and below).
         */
        override suspend fun getForegroundInfo(): ForegroundInfo {
            Channels.ensureCreated(applicationContext)
            return ForegroundInfo(SYNC_NOTIFICATION_ID, buildNotification(0, 0))
        }

        private fun postProgressNotification(
            manager: NotificationManagerCompat,
            imported: Int,
            total: Int,
        ) {
            try {
                manager.notify(SYNC_NOTIFICATION_ID, buildNotification(imported, total))
            } catch (_: SecurityException) {
                // POST_NOTIFICATIONS not granted; progress is still visible in-app.
            }
        }

        private fun buildNotification(
            imported: Int,
            total: Int,
        ): Notification =
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

        companion object {
            const val WORK_NAME = "initial_sync"
            const val PROGRESS_IMPORTED = "imported"
            const val PROGRESS_TOTAL = "total"
            private const val TAG = "InitialSyncWorker"
            private const val SYNC_NOTIFICATION_ID = 70_001
            private const val MAX_ATTEMPTS = 5

            /** The import request; expedited, with resume-friendly retry backoff. */
            fun request(): OneTimeWorkRequest =
                OneTimeWorkRequestBuilder<InitialSyncWorker>()
                    .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                    .setBackoffCriteria(BackoffPolicy.LINEAR, 10, TimeUnit.SECONDS)
                    .build()

            /**
             * Enqueues the one-time import. KEEP means an already
             * enqueued/running import is never cancelled and restarted; a
             * re-enqueue after completion resumes from the durable checkpoint
             * and is a fast no-op when there is nothing new.
             */
            fun enqueue(workManager: WorkManager) {
                workManager.enqueueUniqueWork(WORK_NAME, ExistingWorkPolicy.KEEP, request())
            }

            fun enqueue(context: Context) = enqueue(WorkManager.getInstance(context))
        }
    }
