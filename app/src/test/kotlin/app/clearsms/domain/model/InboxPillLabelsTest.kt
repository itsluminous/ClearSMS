package app.clearsms.domain.model

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class InboxPillLabelsTest {
    @Test
    fun `round trip keeps every override by pill identity`() {
        val labels = mapOf(InboxPill.IMPORTANT to "Bank", InboxPill.SCAM to "Junk", InboxPill.OTP to "Codes")
        assertThat(InboxPillLabels.decode(InboxPillLabels.encode(labels))).isEqualTo(labels)
    }

    @Test
    fun `nothing stored means no overrides`() {
        assertThat(InboxPillLabels.decode(null)).isEmpty()
        assertThat(InboxPillLabels.decode("")).isEmpty()
        assertThat(InboxPillLabels.encode(emptyMap())).isEmpty()
    }

    @Test
    fun `an unknown or stale pill name is dropped, the rest survive`() {
        // A pill removed in a later version, a typo, and a line with no '='.
        val stored = "IMPORTANT=Bank\nGONE_PILL=Old\nnonsense\n=NoName\nOTP=Codes"
        assertThat(InboxPillLabels.decode(stored)).isEqualTo(mapOf(InboxPill.IMPORTANT to "Bank", InboxPill.OTP to "Codes"))
    }

    @Test
    fun `a blank label is a reset and is not stored`() {
        assertThat(InboxPillLabels.sanitize("   ")).isNull()
        assertThat(InboxPillLabels.encode(mapOf(InboxPill.OTP to "  "))).isEmpty()
        assertThat(InboxPillLabels.decode("OTP=   ")).isEmpty()
    }

    @Test
    fun `labels may contain the separator and are split on the first one`() {
        val labels = mapOf(InboxPill.PERSONAL to "A=B")
        assertThat(InboxPillLabels.decode(InboxPillLabels.encode(labels))).isEqualTo(labels)
    }

    @Test
    fun `sanitize trims, collapses whitespace and line breaks, and caps the length`() {
        assertThat(InboxPillLabels.sanitize("  My\n\n  Bank\t ")).isEqualTo("My Bank")
        val long = "x".repeat(InboxPillLabels.MAX_LENGTH + 10)
        assertThat(InboxPillLabels.sanitize(long)).hasLength(InboxPillLabels.MAX_LENGTH)
        // A line break can therefore never corrupt the line-per-entry encoding.
        val encoded = InboxPillLabels.encode(mapOf(InboxPill.OTP to "One\nTwo", InboxPill.SCAM to "Junk"))
        assertThat(InboxPillLabels.decode(encoded)).isEqualTo(mapOf(InboxPill.OTP to "One Two", InboxPill.SCAM to "Junk"))
    }

    @Test
    fun `the first mention of a pill wins over a duplicate line`() {
        assertThat(InboxPillLabels.decode("OTP=First\nOTP=Second")).isEqualTo(mapOf(InboxPill.OTP to "First"))
    }
}
