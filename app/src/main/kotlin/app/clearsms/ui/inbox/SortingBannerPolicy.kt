package app.clearsms.ui.inbox

import androidx.work.WorkInfo
import app.clearsms.work.RecategorizeWorker

/** Progress of the automatic post-update re-sort shown in the inbox banner. */
data class SortingBanner(
    val processed: Int,
    val total: Int,
)

/**
 * Maps the re-sort's WorkManager state to the inbox "Sorting your messages
 * again after the update…" banner.
 *
 * The banner exists to explain a sort the user did NOT ask for, so it shows
 * ONLY for runs tagged [RecategorizeWorker.TAG_AUTO] (the automatic
 * post-update trigger). A manually triggered sort keeps today's behaviour -
 * progress on the Settings row, no inbox banner. The banner disappears the
 * moment the run finishes (any terminal state).
 */
object SortingBannerPolicy {
    fun select(infos: List<WorkInfo>): SortingBanner? {
        val active = infos.firstOrNull { !it.state.isFinished } ?: return null
        if (RecategorizeWorker.TAG_AUTO !in active.tags) return null
        return SortingBanner(
            processed = active.progress.getInt(RecategorizeWorker.PROGRESS_PROCESSED, 0),
            total = active.progress.getInt(RecategorizeWorker.PROGRESS_TOTAL, 0),
        )
    }
}

/**
 * The inbox's stacked top-banner slots in their PINNED top-to-bottom order.
 *
 * Precedence (most important first): the OTP banner is the reason the user
 * opened the app right now and must be the first thing under the app bar;
 * the default-SMS banner is the app's core-functionality warning; the
 * contacts nudge is a lesser permission ask; the sorting banner is pure
 * progress information and always yields to anything actionable. The order
 * is consumed by the inbox screen and pinned by a test - reordering the
 * enum IS the way to change the on-screen order.
 */
enum class InboxBannerSlot {
    OTP,
    DEFAULT_SMS,
    CONTACTS_PERMISSION,
    SORTING,
}
