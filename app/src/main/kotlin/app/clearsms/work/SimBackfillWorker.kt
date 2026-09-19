package app.clearsms.work

import android.content.Context
import android.util.Log
import androidx.hilt.work.HiltWorker
import androidx.work.BackoffPolicy
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import java.util.concurrent.TimeUnit

/**
 * Runs [SimBackfill] once in the background. Enqueued on every cold start
 * (from the Application, next to the auto re-sort check); after the pass has
 * completed for the current [SimBackfill.VERSION] the worker is an instant
 * no-op, so the repeat enqueue costs nothing. Interruptions retry with
 * backoff and resume from the backfill's durable page checkpoint.
 */
@HiltWorker
class SimBackfillWorker
    @AssistedInject
    constructor(
        @Assisted appContext: Context,
        @Assisted params: WorkerParameters,
        private val simBackfill: SimBackfill,
    ) : CoroutineWorker(appContext, params) {
        override suspend fun doWork(): Result =
            try {
                val filled = simBackfill.runIfNeeded()
                if (filled > 0) Log.i(TAG, "SIM backfill filled $filled imported rows")
                Result.success()
            } catch (e: Exception) {
                Log.w(TAG, "SIM backfill attempt $runAttemptCount failed; will resume", e)
                if (runAttemptCount < MAX_ATTEMPTS) Result.retry() else Result.failure()
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
