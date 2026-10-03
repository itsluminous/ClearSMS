package app.clearsms.ui.common

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.Locale

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

    /** What `RelativeTime.Strings.from(context)` resolves to on an English device. */
    private val english = RelativeTime.Strings(today = "Today", yesterday = "Yesterday", locale = Locale.ENGLISH)

    /** The Hindi resources, with the Hindi locale for day and month names. */
    private val hindi = RelativeTime.Strings(today = "आज", yesterday = "कल", locale = Locale.forLanguageTag("hi-IN"))

    @Test
    fun `same day shows time`() {
        assertThat(RelativeTime.format(ms(2026, 7, 27, 9, 5), english, now, zone)).isEqualTo("09:05")
    }

    @Test
    fun `previous day shows yesterday`() {
        assertThat(RelativeTime.format(ms(2026, 7, 26), english, now, zone)).isEqualTo("Yesterday")
    }

    @Test
    fun `within a week shows weekday`() {
        // 23 July 2026 is a Thursday.
        assertThat(RelativeTime.format(ms(2026, 7, 23), english, now, zone)).isEqualTo("Thu")
    }

    @Test
    fun `same year shows day and month`() {
        assertThat(RelativeTime.format(ms(2026, 3, 12), english, now, zone)).isEqualTo("12 Mar")
    }

    @Test
    fun `previous year includes the year`() {
        assertThat(RelativeTime.format(ms(2025, 12, 31), english, now, zone)).isEqualTo("31 Dec 2025")
    }

    @Test
    fun `date label for today and yesterday`() {
        assertThat(RelativeTime.dateLabel(ms(2026, 7, 27, 8, 0), english, now, zone)).isEqualTo("Today")
        assertThat(RelativeTime.dateLabel(ms(2026, 7, 26), english, now, zone)).isEqualTo("Yesterday")
        assertThat(RelativeTime.dateLabel(ms(2026, 1, 2), english, now, zone)).isEqualTo("2 January 2026")
    }

    @Test
    fun `sameDay detects calendar day boundaries`() {
        assertThat(RelativeTime.sameDay(ms(2026, 7, 27, 0, 1), ms(2026, 7, 27, 23, 59), zone)).isTrue()
        assertThat(RelativeTime.sameDay(ms(2026, 7, 26, 23, 59), ms(2026, 7, 27, 0, 1), zone)).isFalse()
    }

    private val devanagari = Regex("[\\u0900-\\u097F]")

    @Test
    fun `the words come from the strings and the day and month names from their locale`() {
        // The two words are whatever the resources say - no English literal
        // survives in RelativeTime - and every date with a name in it is
        // rendered in the locale the strings were resolved for.
        assertThat(RelativeTime.format(ms(2026, 7, 26), hindi, now, zone)).isEqualTo("कल")
        assertThat(RelativeTime.dateLabel(ms(2026, 7, 27, 8, 0), hindi, now, zone)).isEqualTo("आज")
        assertThat(RelativeTime.dateLabel(ms(2026, 7, 26), hindi, now, zone)).isEqualTo("कल")
        for (
        hindiLabel in
        listOf(
            RelativeTime.format(ms(2026, 7, 23), hindi, now, zone),
            RelativeTime.format(ms(2026, 3, 12), hindi, now, zone),
            RelativeTime.format(ms(2025, 12, 31), hindi, now, zone),
            RelativeTime.dateLabel(ms(2026, 1, 2), hindi, now, zone),
        )
        ) {
            assertThat(hindiLabel).containsMatch(devanagari.toPattern())
        }
        // Digits-only output is locale-neutral: the clock reads the same everywhere.
        assertThat(RelativeTime.format(ms(2026, 7, 27, 9, 5), hindi, now, zone)).isEqualTo("09:05")
    }
}
