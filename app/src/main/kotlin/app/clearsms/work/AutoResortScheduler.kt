package app.clearsms.work

import android.util.Log
import androidx.work.WorkManager
import app.clearsms.BuildConfig
import app.clearsms.data.prefs.SettingsRepository
import app.clearsms.di.ApplicationScope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Enqueues the automatic post-update re-sort: after an app update ships new
 * rules/parsers, the whole database is re-categorized WITHOUT the user
 * having to remember Settings → Sort inbox again.
 *
 * Trigger point: cold app start ([onAppStart], next to
 * [app.clearsms.data.repository.UndoManager.onAppStart] in the Application) -
 * an update always kills the process, so the first launch of a new version
 * is necessarily a cold start, and Application.onCreate runs before any
 * WorkManager job executes in the process (a pre-update pending catch-up
 * import can never complete ahead of this check).
 *
 * Conditions, all required (see [check]):
 * - the stored [SettingsRepository.lastSortedVersionCode] differs from the
 *   running [BuildConfig.VERSION_CODE] (0 = never sorted counts as a
 *   mismatch, which self-heals installs predating the marker);
 * - onboarding is complete - the initial import owns the fresh-install
 *   pass and records the marker itself, so this must never race it;
 * - no initial import is currently queued or running (same reason: the
 *   import already classifies everything with the current rules).
 *
 * A re-sort already running is harmless to re-request: the worker's unique
 * KEEP policy makes the enqueue a no-op. The worker records the version on
 * completion (both auto and manual paths), so the trigger fires at most
 * once per version even across repeated cold starts while a sort runs.
 */
@Singleton
class AutoResortScheduler
    @Inject
    constructor(
        private val settings: SettingsRepository,
        private val workManager: WorkManager,
        @ApplicationScope private val scope: CoroutineScope,
    ) {
        /** Fire-and-forget cold-start check; see class KDoc for the conditions. */
        fun onAppStart() {
            scope.launch {
                try {
                    check(BuildConfig.VERSION_CODE)
                } catch (e: Exception) {
                    // Never let a scheduling probe crash startup; the next
                    // cold start retries.
                    Log.w(TAG, "Auto re-sort check failed", e)
                }
            }
        }

        /** Version-injectable core so tests pin the mismatch logic exactly. */
        internal suspend fun check(versionCode: Int) {
            if (!settings.onboardingComplete.first()) return
            if (settings.lastSortedVersionCode.first() == versionCode) return
            val importInfos =
                workManager.getWorkInfosForUniqueWorkFlow(InitialSyncWorker.WORK_NAME).first()
            if (importInfos.any { !it.state.isFinished }) return
            Log.i(TAG, "App updated since last full sort; enqueueing automatic re-sort")
            RecategorizeWorker.enqueue(workManager, auto = true)
        }

        private companion object {
            const val TAG = "AutoResortScheduler"
        }
    }
