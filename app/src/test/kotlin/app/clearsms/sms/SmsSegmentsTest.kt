package app.clearsms.sms

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Billable segment math (GitHub #17): GSM-7 gives 160 septets in one
 * message and 153 per segment once multipart; UCS-2 gives 70 UTF-16 code
 * units single and 67 multipart. All fixture text here is synthetic.
 */
class SmsSegmentsTest {
    @Test
    fun `empty body is zero segments`() {
        assertThat(SmsSegments.count("")).isEqualTo(0)
    }

    @Test
    fun `gsm single segment boundary is 160`() {
        assertThat(SmsSegments.count("a".repeat(160))).isEqualTo(1)
        assertThat(SmsSegments.count("a".repeat(161))).isEqualTo(2)
    }

    @Test
    fun `gsm multipart segments are 153 each`() {
        assertThat(SmsSegments.count("a".repeat(306))).isEqualTo(2)
        assertThat(SmsSegments.count("a".repeat(307))).isEqualTo(3)
    }

    @Test
    fun `gsm-7 contains non-ascii letters that stay one septet`() {
        // The table has é à ö ñ ü Å ß etc. - never UCS-2, never double cost.
        val text = "Ärger üben à Paris, Señor Ñoño était ß-Å"
        assertThat(SmsSegments.isGsmText(text)).isTrue()
        assertThat(GsmAlphabet.septets(text)).isEqualTo(text.length)
    }

    @Test
    fun `extended table characters cost two septets`() {
        // ^ { } \ [ ~ ] | € each ride an escape.
        assertThat(GsmAlphabet.septets("[]")).isEqualTo(4)
        assertThat(GsmAlphabet.septets("€")).isEqualTo(2)
        // 158 letters + € = 160 septets: still one message...
        assertThat(SmsSegments.count("a".repeat(158) + "€")).isEqualTo(1)
        // ...159 letters + € = 161 septets: two.
        assertThat(SmsSegments.count("a".repeat(159) + "€")).isEqualTo(2)
    }

    @Test
    fun `one non-gsm character flips the whole message to ucs-2`() {
        // 100 plain letters fit one GSM segment; adding a single č makes
        // the message UCS-2 - 101 chars over 67-per-segment = 2 segments.
        val plain = "a".repeat(100)
        assertThat(SmsSegments.count(plain)).isEqualTo(1)
        assertThat(SmsSegments.count(plain + "č")).isEqualTo(2)
    }

    @Test
    fun `ucs-2 single segment boundary is 70`() {
        assertThat(SmsSegments.count("č" + "a".repeat(69))).isEqualTo(1)
        assertThat(SmsSegments.count("č" + "a".repeat(70))).isEqualTo(2)
    }

    @Test
    fun `ucs-2 multipart segments are 67 each`() {
        assertThat(SmsSegments.count("č" + "a".repeat(133))).isEqualTo(2)
        assertThat(SmsSegments.count("č" + "a".repeat(134))).isEqualTo(3)
    }

    @Test
    fun `an emoji counts as two ucs-2 code units`() {
        // 😀 is a surrogate pair: 69 letters + emoji = 71 units = 2 segments.
        assertThat(SmsSegments.count("a".repeat(68) + "\uD83D\uDE00")).isEqualTo(1)
        assertThat(SmsSegments.count("a".repeat(69) + "\uD83D\uDE00")).isEqualTo(2)
    }
}
