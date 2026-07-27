package app.clearsms.ui.common

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId

class RelativeTimeTest {
    private val zone = ZoneId.of("Asia/Kolkata")

    private fun ms(
        year: Int,
        month: Int,
        day: Int,
        hour: Int = 12,
        minute: Int = 0,
    ): Long =
        LocalDateTime
            .of(year, month, day, hour, minute)
            .atZone(zone)
            .toInstant()
            .toEpochMilli()

    private val now = ms(2026, 7, 27, 15, 30)

    @Test
    fun `same day shows time`() {
        assertThat(RelativeTime.format(ms(2026, 7, 27, 9, 5), now, zone)).isEqualTo("09:05")
    }

    @Test
    fun `previous day shows yesterday`() {
        assertThat(RelativeTime.format(ms(2026, 7, 26), now, zone)).isEqualTo("Yesterday")
    }

    @Test
    fun `within a week shows weekday`() {
        // 23 July 2026 is a Thursday.
        assertThat(RelativeTime.format(ms(2026, 7, 23), now, zone)).isEqualTo("Thu")
    }

    @Test
    fun `same year shows day and month`() {
        assertThat(RelativeTime.format(ms(2026, 3, 12), now, zone)).isEqualTo("12 Mar")
    }

    @Test
    fun `previous year includes the year`() {
        assertThat(RelativeTime.format(ms(2025, 12, 31), now, zone)).isEqualTo("31 Dec 2025")
    }

    @Test
    fun `date label for today and yesterday`() {
        assertThat(RelativeTime.dateLabel(ms(2026, 7, 27, 8, 0), now, zone)).isEqualTo("Today")
        assertThat(RelativeTime.dateLabel(ms(2026, 7, 26), now, zone)).isEqualTo("Yesterday")
        assertThat(RelativeTime.dateLabel(ms(2026, 1, 2), now, zone)).isEqualTo("2 January 2026")
    }

    @Test
    fun `sameDay detects calendar day boundaries`() {
        assertThat(RelativeTime.sameDay(ms(2026, 7, 27, 0, 1), ms(2026, 7, 27, 23, 59), zone)).isTrue()
        assertThat(RelativeTime.sameDay(ms(2026, 7, 26, 23, 59), ms(2026, 7, 27, 0, 1), zone)).isFalse()
    }
}
