package app.clearsms.ui.common

import android.content.Context
import android.text.format.DateFormat
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import app.clearsms.domain.model.TimeFormat
import java.time.format.DateTimeFormatter
import java.time.temporal.TemporalAccessor
import java.util.Locale

/**
 * The ONE place a clock time is formatted for the user (GitHub #158).
 *
 * Before this object the app disagreed with itself: `RelativeTime` always
 * wrote "14:05" while the metadata line and details dialog honoured the
 * phone's 12/24-hour setting, so a 12-hour phone read "14:05" in the inbox
 * and "2:05 pm" under the same bubble. Now [is24Hour] resolves the user's
 * [TimeFormat] (default: follow the phone) and [clock] owns the four
 * patterns; `RelativeTime`, `MessageMetadata` and the schedule picker all
 * go through here. `ClockFormatContractTest` fails the build if a clock
 * pattern or `is24HourFormat` call appears anywhere else in the app.
 *
 * Deliberately NOT routed through here, because they are machine formats
 * that must never change shape with a UI preference: the diagnostic log
 * stamps (`DiagBuffer`), the diagnostic report file name
 * (`DiagnosticReport`) and the backup file names (`BackupFileNames`).
 */
object ClockFormat {
    // Patterns only - the locale that renders the am/pm marker is applied per
    // call via withLocale(), because a DateTimeFormatter freezes the locale
    // it was created with and these are process-wide singletons.
    private val time24 = DateTimeFormatter.ofPattern("HH:mm")
    private val time12 = DateTimeFormatter.ofPattern("h:mm a")
    private val time24Seconds = DateTimeFormatter.ofPattern("HH:mm:ss")
    private val time12Seconds = DateTimeFormatter.ofPattern("h:mm:ss a")

    /**
     * Whether clocks are 24-hour for [preference]: the phone's own setting
     * for [TimeFormat.SYSTEM], else the fixed choice.
     */
    fun is24Hour(
        context: Context,
        preference: TimeFormat,
    ): Boolean =
        when (preference) {
            TimeFormat.SYSTEM -> DateFormat.is24HourFormat(context)
            TimeFormat.HOUR_12 -> false
            TimeFormat.HOUR_24 -> true
        }

    /**
     * The wall-clock part of [time]: "14:05" or "2:05 pm" (with seconds,
     * "14:05:07" / "2:05:07 pm"). The am/pm marker comes from [locale]'s
     * CLDR data - the same the system clock uses - lowercased, as the
     * metadata line always showed it.
     */
    fun clock(
        time: TemporalAccessor,
        is24Hour: Boolean,
        locale: Locale,
        withSeconds: Boolean = false,
    ): String =
        when {
            is24Hour && withSeconds -> time24Seconds.withLocale(locale).format(time)
            is24Hour -> time24.withLocale(locale).format(time)
            withSeconds -> time12Seconds.withLocale(locale).format(time).lowercase(locale)
            else -> time12.withLocale(locale).format(time).lowercase(locale)
        }
}

/**
 * The user's [TimeFormat], provided once at the app root (see `ClearSmsApp`)
 * so every screen that writes a clock reads the same preference.
 */
val LocalTimeFormat = staticCompositionLocalOf { TimeFormat.SYSTEM }

/**
 * [ClockFormat.is24Hour] in composition. Keyed on the preference AND the
 * configuration: for a SYSTEM user the phone's answer can change with the
 * language (a locale carries its own 12/24 default when the switch is
 * unset), and the old `remember { }` without keys froze the first answer
 * for the life of the composition.
 */
@Composable
fun rememberIs24Hour(): Boolean {
    val context = LocalContext.current
    val configuration = LocalConfiguration.current
    val preference = LocalTimeFormat.current
    return remember(configuration, preference) { ClockFormat.is24Hour(context, preference) }
}
