package app.clearsms.work

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.BackoffPolicy
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import app.clearsms.diagnostics.Diag
import app.clearsms.diagnostics.DiagField.Companion.count
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.flow.first
import java.util.concurrent.TimeUnit

/**
 * Runs the one-time provider backfills - [SimBackfill], then
 * [SentTimeBackfill] - once in the background, SEQUENTIALLY (never two
 * provider walks at once). Enqueued on every cold start (from the
 * Application, next to the auto re-sort check); after both passes have
 * completed for their current VERSION the worker is an instant no-op, so
 * the repeat enqueue costs nothing. Interruptions retry with backoff and
 * resume from each backfill's durable page checkpoint.
 *
 * The backfills must never compete with a foreground import: both walk the
 * same provider and write the same database on the same IO dispatcher, and
 * that contention is what made the initial import visibly slower. While an
 * [InitialSyncWorker] run is enqueued or running, this worker defers itself
 * with [Result.retry] instead of doing any work - after the import finishes
 * the retried passes either find nothing to fill (fresh installs mark the
 * versions done) or run alone.
 */
@HiltWorker
class SimBackfillWorker
    @AssistedInject
    constructor(
        @Assisted appContext: Context,
        @Assisted params: WorkerParameters,
        private val simBackfill: SimBackfill,
        private val sentTimeBackfill: SentTimeBackfill,
    ) : CoroutineWorker(appContext, params) {
        override suspend fun doWork(): Result {
            if (importActive()) return Result.retry()
            return try {
                val filled = simBackfill.runIfNeeded()
                if (filled > 0) Diag.i(TAG, "SIM backfill filled rows", count("filled", filled))
                // Strictly after the SIM pass: one provider walk at a time.
                val sentFilled = sentTimeBackfill.runIfNeeded()
                if (sentFilled > 0) Diag.i(TAG, "sent-time backfill filled rows", count("filled", sentFilled))
                Result.success()
            } catch (e: Exception) {
                Diag.w(TAG, "provider backfill attempt failed; will resume", e, count("attempt", runAttemptCount))
                if (runAttemptCount < MAX_ATTEMPTS) Result.retry() else Result.failure()
            }
        }

        /** True while an initial/catch-up import is enqueued or running. */
        private suspend fun importActive(): Boolean =
            try {
                WorkManager
                    .getInstance(applicationContext)
                    .getWorkInfosForUniqueWorkFlow(InitialSyncWorker.WORK_NAME)
                    .first()
                    .any { it.state == WorkInfo.State.ENQUEUED || it.state == WorkInfo.State.RUNNING }
            } catch (e: Exception) {
                Diag.w(TAG, "cannot read import work state; running backfill anyway", e)
                false
            }

        companion object {
            const val WORK_NAME = "sim_backfill"
            private const val TAG = "SimBackfillWorker"
            private const val MAX_ATTEMPTS = 5

            /** Enqueues the one-shot; KEEP never restarts a queued/running pass. */
            fun enqueue(context: Context) {
                WorkManager.getInstance(context).enqueueUniqueWork(
                    WORK_NAME,
                    ExistingWorkPolicy.KEEP,
                    OneTimeWorkRequestBuilder<SimBackfillWorker>()
                        .setBackoffCriteria(BackoffPolicy.LINEAR, 30, TimeUnit.SECONDS)
                        .build(),
                )
            }
        }
    }
