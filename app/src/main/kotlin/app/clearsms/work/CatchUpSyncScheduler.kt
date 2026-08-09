package app.clearsms.work

import android.content.Context
import android.provider.Telephony
import android.util.Log
import androidx.work.WorkManager
import app.clearsms.data.db.MessageDao
import app.clearsms.di.IoDispatcher
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Enqueues catch-up runs of [InitialSyncWorker] when messages may have landed
 * in the system SMS provider while Clear SMS was not the default app.
 *
 * While another app is default, incoming messages are written to the provider
 * by that app and never reach [app.clearsms.receiver.SmsReceiver] - without a
 * catch-up they would stay invisible forever. Two triggers close the gap:
 *
 * 1. **Role regained** ([onRoleChecked] with `regained = true`) - the
 *    inbox banner's role check observed an ABSENT→HELD transition (grant via
 *    the banner, or an external switch detected on resume). Enqueue
 *    unconditionally: the import resumes from its durable checkpoint, so
 *    rows the checkpoint already passed are skipped and re-scanned rows are
 *    deduplicated by the unique `systemSmsId` index. A no-gap run is a fast
 *    no-op.
 * 2. **Cold-start gap probe** (once per process, when the role is held) -
 *    covers a switch-away-and-back that happened entirely while the app was
 *    dead, where no transition is observable. Costs two single-row indexed
 *    queries: the provider's `MAX(_id)` (via `ORDER BY _id DESC LIMIT 1` on
 *    the primary key) and Room's `MAX(systemSmsId)` (backed by the unique
 *    index) - cheap enough to run on every cold start. A provider max above
 *    ours means rows exist that we never saw; the worker is enqueued. The
 *    probe can rarely over-trigger (e.g. the newest provider row is a
 *    draft, which the importer skips), in which case the run terminates
 *    immediately with nothing to import.
 *
 * Catch-up runs reuse the initial import wholesale: unique work (KEEP),
 * durable checkpoint, and the FULL classification pipeline (categorization,
 * transaction extraction, reminders). Crucially the importer persists pages
 * in bulk and posts NO per-message notifications - only the worker's single
 * low-importance progress notification, cancelled on completion - so
 * catching up on a day of missed messages never storms the shade the way
 * live [app.clearsms.receiver.SmsReceiver] deliveries would.
 */
@Singleton
class CatchUpSyncScheduler
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
        private val messageDao: MessageDao,
        private val workManager: WorkManager,
        @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
    ) {
        private val probed = AtomicBoolean(false)

        /**
         * Reacts to a default-SMS role check from the inbox. [regained] is
         * [app.clearsms.ui.inbox.DefaultSmsBannerState.onRoleChecked]'s
         * ABSENT→HELD transition signal; the first check of a process
         * additionally runs the gap probe.
         */
        suspend fun onRoleChecked(
            held: Boolean,
            regained: Boolean,
        ) {
            if (!held) return
            if (regained) {
                InitialSyncWorker.enqueue(workManager)
                return
            }
            if (probed.getAndSet(true)) return
            withContext(ioDispatcher) {
                val providerMax = providerMaxId() ?: return@withContext
                val localMax = messageDao.maxSystemSmsId() ?: 0L
                if (providerMax > localMax) {
                    Log.i(TAG, "Provider max _id $providerMax exceeds local $localMax; scheduling catch-up import")
                    InitialSyncWorker.enqueue(workManager)
                }
            }
        }

        /** Highest `_id` in the system SMS provider, or null when unreadable/empty. */
        private fun providerMaxId(): Long? =
            try {
                context.contentResolver
                    .query(
                        Telephony.Sms.CONTENT_URI,
                        arrayOf(Telephony.Sms._ID),
                        null,
                        null,
                        "${Telephony.Sms._ID} DESC LIMIT 1",
                    )?.use { if (it.moveToFirst()) it.getLong(0) else null }
            } catch (e: Exception) {
                Log.w(TAG, "Cannot probe the system SMS provider", e)
                null
            }

        private companion object {
            const val TAG = "CatchUpSyncScheduler"
        }
    }
