package app.clearsms.ui.common

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Formats message timestamps the way inbox rows expect ("14:05", "Yesterday", "Tue", "12 Mar"). */
object RelativeTime {
    private val timeFormat = DateTimeFormatter.ofPattern("HH:mm", Locale.ENGLISH)
    private val dayFormat = DateTimeFormatter.ofPattern("EEE", Locale.ENGLISH)
    private val dateFormat = DateTimeFormatter.ofPattern("d MMM", Locale.ENGLISH)
    private val dateYearFormat = DateTimeFormatter.ofPattern("d MMM yyyy", Locale.ENGLISH)

    fun format(
        timestampMs: Long,
        nowMs: Long = System.currentTimeMillis(),
        zone: ZoneId = ZoneId.systemDefault(),
    ): String {
        val then = Instant.ofEpochMilli(timestampMs).atZone(zone)
        val thenDate = then.toLocalDate()
        val nowDate = Instant.ofEpochMilli(nowMs).atZone(zone).toLocalDate()
        return when {
            thenDate == nowDate -> timeFormat.format(then)
            thenDate == nowDate.minusDays(1) -> "Yesterday"
            thenDate.isAfter(nowDate.minusDays(7)) -> dayFormat.format(then)
            thenDate.year == nowDate.year -> dateFormat.format(then)
            else -> dateYearFormat.format(then)
        }
    }

    /** Date-separator label for conversation view ("Today", "Yesterday", "12 March 2026"). */
    fun dateLabel(
        timestampMs: Long,
        nowMs: Long = System.currentTimeMillis(),
        zone: ZoneId = ZoneId.systemDefault(),
    ): String {
        val thenDate = Instant.ofEpochMilli(timestampMs).atZone(zone).toLocalDate()
        val nowDate = Instant.ofEpochMilli(nowMs).atZone(zone).toLocalDate()
        return when (thenDate) {
            nowDate -> "Today"
            nowDate.minusDays(1) -> "Yesterday"
            else -> DateTimeFormatter.ofPattern("d MMMM yyyy", Locale.ENGLISH).format(thenDate)
        }
    }

    /** True when both timestamps fall on the same calendar day. */
    fun sameDay(
        aMs: Long,
        bMs: Long,
        zone: ZoneId = ZoneId.systemDefault(),
    ): Boolean = toLocalDate(aMs, zone) == toLocalDate(bMs, zone)

    private fun toLocalDate(
        ms: Long,
        zone: ZoneId,
    ): LocalDate = Instant.ofEpochMilli(ms).atZone(zone).toLocalDate()
}
