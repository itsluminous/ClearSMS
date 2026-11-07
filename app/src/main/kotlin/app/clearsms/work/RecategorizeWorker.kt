package app.clearsms.work

import android.app.Notification
import android.content.Context
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.OneTimeWorkRequest
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import app.clearsms.BuildConfig
import app.clearsms.R
import app.clearsms.data.prefs.SettingsRepository
import app.clearsms.data.repository.MessageRepository
import app.clearsms.diagnostics.Diag
import app.clearsms.diagnostics.DiagField.Companion.count
import app.clearsms.notification.Channels
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.CancellationException

/**
 * Manual "Sort messages again" re-categorization over the whole database.
 *
 * Mirrors [InitialSyncWorker]'s durability model - unique work with KEEP (a
 * second tap while one is running is a no-op), expedited with a foreground
 * fallback below Android 12, and an ongoing progress notification - so a
 * 14k-message re-sort survives navigation, screen-off and process death
 * instead of dying with a ViewModel scope.
 *
 * Progress (processed / total) is published via WorkManager progress [Data],
 * observed by the Settings screen through `getWorkInfosForUniqueWorkFlow`;
 * the UI never owns the run. Cancellation is safe at any point: the
 * repository commits one page per transaction, so a cancelled run leaves
 * every completed page fully consistent and nothing half-written.
 *
 * **Resume.** A run the SYSTEM stopped (expedited quota, process death) is
 * rescheduled by WorkManager and continues from the durable
 * [RecategorizeCheckpointPrefs] instead of the first row - without that, a
 * device that kills background work restarted the sort from zero on every
 * background receive, and every live write queued behind it (issue #153).
 * Which runs resume:
 * - the automatic post-update run always (the checkpoint store itself
 *   discards a checkpoint from another app version, so the version marker
 *   is only ever recorded by a pass that ran entirely under it);
 * - a manual "Sort messages again" only when it is a RE-attempt
 *   (`runAttemptCount > 0`); the user's first tap always starts from the
 *   first row, because they may have tapped precisely to apply a rule the
 *   half-finished pass did not know - a body-only rule the editor tells them
 *   to apply with a full re-sort.
 * CoroutineWorker cancels the coroutine when WorkManager stops the worker,
 * and the repository loop yields between pages, so a stop leaves the loop
 * at a page boundary with the checkpoint already written for the committed
 * part.
 */
@HiltWorker
class RecategorizeWorker
    @AssistedInject
    constructor(
        @Assisted appContext: Context,
        @Assisted params: WorkerParameters,
        private val messageRepository: MessageRepository,
        private val settings: SettingsRepository,
        private val checkpoints: RecategorizeCheckpointPrefs,
    ) : CoroutineWorker(appContext, params) {
        override suspend fun doWork(): Result {
            Channels.ensureCreated(applicationContext)
            val manager = NotificationManagerCompat.from(applicationContext)
            return try {
                val auto = TAG_AUTO in tags
                if (!auto && runAttemptCount == 0) checkpoints.clear()
                val count =
                    messageRepository.recategorizeAll(checkpoints) { processed, total ->
                        setProgress(
                            Data
                                .Builder()
                                .putInt(PROGRESS_PROCESSED, processed)
                                .putInt(PROGRESS_TOTAL, total)
                                .build(),
                        )
                        postProgressNotification(manager, processed, total)
                    }
                // Both trigger paths (manual tap AND the automatic
                // post-update run) record the version as fully sorted, so
                // the auto re-sort never re-fires for a version whose full
                // pass already completed.
                settings.setLastSortedVersionCode(BuildConfig.VERSION_CODE)
                Diag.i(TAG, "re-sort finished", count("messages", count))
                Result.success(workDataOf(OUTPUT_COUNT to count))
            } catch (e: CancellationException) {
                // User-initiated cancel: pages already committed stay valid.
                throw e
            } catch (e: Exception) {
                // Content-free by convention (no message data in logs). A
                // failed re-sort is simply re-triggered by the user; no retry
                // loop over the whole database.
                Diag.e(TAG, "re-sort failed", e)
                Result.failure()
            } finally {
                manager.cancel(NOTIFICATION_ID)
            }
        }

        /** Foreground promotion for the expedited run on Android 11 and below. */
        override suspend fun getForegroundInfo(): ForegroundInfo {
            Channels.ensureCreated(applicationContext)
            return ForegroundInfo(NOTIFICATION_ID, buildNotification(0, 0))
        }

        private fun postProgressNotification(
            manager: NotificationManagerCompat,
            processed: Int,
            total: Int,
        ) {
            try {
                manager.notify(NOTIFICATION_ID, buildNotification(processed, total))
            } catch (_: SecurityException) {
                // POST_NOTIFICATIONS not granted; progress is still visible in-app.
            }
        }

        private fun buildNotification(
            processed: Int,
            total: Int,
        ): Notification =
            NotificationCompat
                .Builder(applicationContext, Channels.SYNC)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(applicationContext.getString(R.string.resort_title))
                .setContentText(applicationContext.getString(R.string.sync_progress, processed, total))
                .setProgress(total, processed, total == 0)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setCategory(NotificationCompat.CATEGORY_PROGRESS)
                .build()

        companion object {
            const val WORK_NAME = "recategorize"

            /** Tag marking an AUTOMATICALLY triggered (post-update) run. */
            const val TAG_AUTO = "recategorize_auto"
            const val PROGRESS_PROCESSED = "processed"
            const val PROGRESS_TOTAL = "total"

            /** Output key: how many messages the finished run re-categorized. */
            const val OUTPUT_COUNT = "count"
            private const val TAG = "RecategorizeWorker"
            private const val NOTIFICATION_ID = 70_002

            fun request(auto: Boolean = false): OneTimeWorkRequest =
                OneTimeWorkRequestBuilder<RecategorizeWorker>()
                    .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                    .apply { if (auto) addTag(TAG_AUTO) }
                    .build()

            /**
             * KEEP: triggering while a re-sort is running never restarts it.
             * [auto] tags the request as the AUTOMATIC post-update run - the
             * inbox shows an explanatory banner only for that tag, because
             * the banner exists to explain a sort the user did NOT ask for
             * (manual runs keep the settings-row progress alone).
             */
            fun enqueue(
                workManager: WorkManager,
                auto: Boolean = false,
            ) {
                workManager.enqueueUniqueWork(WORK_NAME, ExistingWorkPolicy.KEEP, request(auto))
            }

            fun cancel(workManager: WorkManager) {
                workManager.cancelUniqueWork(WORK_NAME)
            }
        }
    }
