package app.clearsms.ui.conversation

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Pure helpers behind the tap-to-reveal metadata line under a bubble. */
object MessageMetadata {
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
