package app.clearsms.ui.conversation

import app.clearsms.data.db.DeliveryStatus
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Pure helpers behind the tap-to-reveal metadata line under a bubble. */
object MessageMetadata {
    /** Where a bubble tap is routed (see [tapAction]). */
    enum class TapAction {
        /** Multi-select is active: the tap toggles the message's selection. */
        TOGGLE_SELECTION,

        /** The send failed: the tap offers Retry / Delete for the message. */
        OFFER_RETRY,

        /** Scheduled: the tap offers Send now / Edit time / Cancel. */
        OFFER_SCHEDULE_ACTIONS,

        /** An incoming MMS whose download failed: the tap offers Retry / Delete. */
        OFFER_MMS_RETRY,

        /** Default: the tap toggles the metadata/details expansion. */
        TOGGLE_DETAILS,
    }

    /**
     * Routes a bubble tap. Selection mode always wins (taps must keep
     * toggling selection there). Outside it, a FAILED outgoing bubble - the
     * red "Not sent" - offers Retry/Delete on tap instead of expanding
     * metadata: recovering the message is what the user wants from that
     * bubble, and its status is already visible without expansion. A
     * SCHEDULED bubble likewise offers its actions (send now / edit time /
     * cancel), and an incoming MMS that could not be downloaded offers
     * Retry/Delete for the same reason.
     */
    fun tapAction(
        selectionActive: Boolean,
        outgoing: Boolean,
        deliveryStatus: DeliveryStatus?,
        mmsDownloadFailed: Boolean = false,
    ): TapAction =
        when {
            selectionActive -> TapAction.TOGGLE_SELECTION
            outgoing && deliveryStatus == DeliveryStatus.FAILED -> TapAction.OFFER_RETRY
            outgoing && deliveryStatus == DeliveryStatus.SCHEDULED -> TapAction.OFFER_SCHEDULE_ACTIONS
            !outgoing && mmsDownloadFailed -> TapAction.OFFER_MMS_RETRY
            else -> TapAction.TOGGLE_DETAILS
        }

    private val date = DateTimeFormatter.ofPattern("d MMM yyyy", Locale.ENGLISH)
    private val time24 = DateTimeFormatter.ofPattern("HH:mm", Locale.ENGLISH)
    private val time12 = DateTimeFormatter.ofPattern("h:mm a", Locale.ENGLISH)

    /**
     * Exact date + time of a message, honouring the device's 12/24-hour
     * setting: "26 Jul 2026, 16:59" or "26 Jul 2026, 4:59 pm".
     */
    fun timestampLabel(
        timestampMs: Long,
        is24Hour: Boolean,
        zone: ZoneId = ZoneId.systemDefault(),
    ): String {
        val then = Instant.ofEpochMilli(timestampMs).atZone(zone)
        val time = if (is24Hour) time24.format(then) else time12.format(then).lowercase(Locale.ENGLISH)
        return "${date.format(then)}, $time"
    }

    /**
     * Single-expansion toggle for the metadata line: at most one message is
     * expanded at a time (an expansion replaces the previous one - chosen
     * because stacked open metadata lines add noise without value). Taps
     * while multi-select is active toggle SELECTION instead, so the expanded
     * message is left untouched.
     *
     * @return the id that should be expanded after tapping [tappedId].
     */
    fun onTap(
        expandedId: Long?,
        tappedId: Long,
        selectionActive: Boolean,
    ): Long? =
        when {
            selectionActive -> expandedId
            expandedId == tappedId -> null
            else -> tappedId
        }
}
