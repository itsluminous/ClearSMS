package app.clearsms.ui.common

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.core.os.ConfigurationCompat
import app.clearsms.R
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Formats message timestamps the way inbox rows expect ("14:05", "Yesterday",
 * "Tue", "12 Mar"), in the app's language: the words come from string
 * resources and the weekday/month names from the display locale, both
 * carried in [Strings]. This object stays pure - it never reads a Context
 * itself - so the formatting is unit-testable with an explicit [Strings].
 */
object RelativeTime {
    /**
     * The locale-dependent half of a relative time: the two words that are
     * not dates at all, and the [locale] whose day and month names are used.
     * Build it with [from] (or [rememberRelativeTimeStrings] in composition);
     * tests construct it directly.
     */
    class Strings(
        val today: String,
        val yesterday: String,
        val locale: Locale,
    ) {
        companion object {
            /** Resolved against [context]'s current resources, so it follows the app language. */
            fun from(context: Context): Strings =
                Strings(
                    today = context.getString(R.string.date_today),
                    yesterday = context.getString(R.string.date_yesterday),
                    locale = displayLocale(context),
                )
        }
    }

    // Patterns only - the locale that renders them is applied per call via
    // withLocale(), because a DateTimeFormatter freezes the locale it was
    // created with and these are process-wide singletons.
    private val timeFormat = DateTimeFormatter.ofPattern("HH:mm")
    private val dayFormat = DateTimeFormatter.ofPattern("EEE")
    private val dateFormat = DateTimeFormatter.ofPattern("d MMM")
    private val dateYearFormat = DateTimeFormatter.ofPattern("d MMM yyyy")
    private val fullDateFormat = DateTimeFormatter.ofPattern("d MMMM yyyy")

    fun format(
        timestampMs: Long,
        strings: Strings,
        nowMs: Long = System.currentTimeMillis(),
        zone: ZoneId = ZoneId.systemDefault(),
    ): String {
        val then = Instant.ofEpochMilli(timestampMs).atZone(zone)
        val thenDate = then.toLocalDate()
        val nowDate = Instant.ofEpochMilli(nowMs).atZone(zone).toLocalDate()
        val locale = strings.locale
        return when {
            thenDate == nowDate -> timeFormat.withLocale(locale).format(then)
            thenDate == nowDate.minusDays(1) -> strings.yesterday
            thenDate.isAfter(nowDate.minusDays(7)) -> dayFormat.withLocale(locale).format(then)
            thenDate.year == nowDate.year -> dateFormat.withLocale(locale).format(then)
            else -> dateYearFormat.withLocale(locale).format(then)
        }
    }

    /** Date-separator label for conversation view ("Today", "Yesterday", "12 March 2026"). */
    fun dateLabel(
        timestampMs: Long,
        strings: Strings,
        nowMs: Long = System.currentTimeMillis(),
        zone: ZoneId = ZoneId.systemDefault(),
    ): String {
        val thenDate = Instant.ofEpochMilli(timestampMs).atZone(zone).toLocalDate()
        val nowDate = Instant.ofEpochMilli(nowMs).atZone(zone).toLocalDate()
        return when (thenDate) {
            nowDate -> strings.today
            nowDate.minusDays(1) -> strings.yesterday
            else -> fullDateFormat.withLocale(strings.locale).format(thenDate)
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

/**
 * The locale user-facing dates, times and numbers are rendered in: the
 * app's language (the Android 13+ per-app setting, else the system's), read
 * from [context]'s resources so it is the same locale its strings resolve in.
 * This is the DISPLAY locale - anything parsed, stored, compared or written
 * into a file name keeps its own fixed locale (see `BackupFileNames`,
 * `ReminderParser`).
 */
fun displayLocale(context: Context): Locale = ConfigurationCompat.getLocales(context.resources.configuration)[0] ?: Locale.getDefault()

/** [displayLocale] in composition - recomputed when the configuration (language) changes. */
@Composable
fun rememberDisplayLocale(): Locale {
    val configuration = LocalConfiguration.current
    return remember(configuration) { ConfigurationCompat.getLocales(configuration)[0] ?: Locale.getDefault() }
}

/** [RelativeTime.Strings] for composition - recomputed when the configuration (language) changes. */
@Composable
fun rememberRelativeTimeStrings(): RelativeTime.Strings {
    val context = LocalContext.current
    val configuration = LocalConfiguration.current
    return remember(configuration) { RelativeTime.Strings.from(context) }
}
