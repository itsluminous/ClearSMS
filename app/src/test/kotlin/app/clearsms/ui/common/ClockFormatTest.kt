package app.clearsms.ui.common

import android.content.Context
import android.provider.Settings
import androidx.test.core.app.ApplicationProvider
import app.clearsms.domain.model.TimeFormat
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.Locale

/**
 * The one clock resolver behind every time the app writes (GitHub #158):
 * the preference resolves to a 12/24 flag, and the flag picks the pattern.
 */
@RunWith(RobolectricTestRunner::class)
class ClockFormatTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val zone = ZoneId.of("Asia/Kolkata")
    private val afternoon = LocalDateTime.of(2026, 7, 26, 16, 59, 7).atZone(zone)
    private val morning = LocalDateTime.of(2026, 7, 26, 9, 5, 0).atZone(zone)

    private fun systemClock(value: String?) {
        Settings.System.putString(context.contentResolver, Settings.System.TIME_12_24, value)
    }

    @Test
    fun `the fixed choices ignore the phone`() {
        systemClock("24")
        assertThat(ClockFormat.is24Hour(context, TimeFormat.HOUR_12)).isFalse()
        assertThat(ClockFormat.is24Hour(context, TimeFormat.HOUR_24)).isTrue()
        systemClock("12")
        assertThat(ClockFormat.is24Hour(context, TimeFormat.HOUR_12)).isFalse()
        assertThat(ClockFormat.is24Hour(context, TimeFormat.HOUR_24)).isTrue()
    }

    @Test
    fun `SYSTEM - the default - follows Android's own 24-hour switch`() {
        // What MessageMetadata already did before #158, and what a new user
        // expects: the inbox now agrees with the details dialog by default.
        systemClock("24")
        assertThat(ClockFormat.is24Hour(context, TimeFormat.SYSTEM)).isTrue()
        systemClock("12")
        assertThat(ClockFormat.is24Hour(context, TimeFormat.SYSTEM)).isFalse()
    }

    @Test
    fun `the default preference is SYSTEM`() {
        assertThat(TimeFormat.entries.first()).isEqualTo(TimeFormat.SYSTEM)
    }

    @Test
    fun `24-hour clocks - with and without seconds`() {
        assertThat(ClockFormat.clock(afternoon, is24Hour = true, locale = Locale.ENGLISH)).isEqualTo("16:59")
        assertThat(ClockFormat.clock(morning, is24Hour = true, locale = Locale.ENGLISH)).isEqualTo("09:05")
        assertThat(ClockFormat.clock(afternoon, is24Hour = true, locale = Locale.ENGLISH, withSeconds = true)).isEqualTo("16:59:07")
    }

    @Test
    fun `12-hour clocks drop the leading zero and lowercase the marker`() {
        assertThat(ClockFormat.clock(afternoon, is24Hour = false, locale = Locale.ENGLISH)).isEqualTo("4:59 pm")
        assertThat(ClockFormat.clock(morning, is24Hour = false, locale = Locale.ENGLISH)).isEqualTo("9:05 am")
        assertThat(ClockFormat.clock(afternoon, is24Hour = false, locale = Locale.ENGLISH, withSeconds = true)).isEqualTo("4:59:07 pm")
    }

    @Test
    fun `RelativeTime and MessageMetadata write the same clock for the same instant`() {
        // The bug behind #158: a 12-hour phone read "16:59" in the inbox row
        // and "4:59 pm" on the metadata line under the very same bubble.
        val ms = afternoon.toInstant().toEpochMilli()
        for (is24Hour in listOf(true, false)) {
            val strings = RelativeTime.Strings("Today", "Yesterday", Locale.ENGLISH, is24Hour)
            val row = RelativeTime.format(ms, strings, nowMs = ms, zone = zone)
            val metadata =
                app.clearsms.ui.conversation.MessageMetadata
                    .timestampLabel(ms, is24Hour, zone, Locale.ENGLISH)
            assertThat(metadata).endsWith(", $row")
        }
    }
}
