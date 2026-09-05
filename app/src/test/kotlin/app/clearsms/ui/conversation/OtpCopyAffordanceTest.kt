package app.clearsms.ui.conversation

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * The always-visible Copy OTP button under an OTP bubble: shown exactly for
 * messages carrying an extracted OTP, and hidden while selection mode is
 * active (a live tap target during multi-select would steal taps meant to
 * toggle selection; the selection bar already offers Copy OTP there).
 */
class OtpCopyAffordanceTest {
    @Test
    fun `visible for a message with an extracted OTP`() {
        assertThat(OtpCopyAffordance.visible("123456", selectionActive = false)).isTrue()
    }

    @Test
    fun `absent for messages without an OTP`() {
        assertThat(OtpCopyAffordance.visible(null, selectionActive = false)).isFalse()
        assertThat(OtpCopyAffordance.visible("", selectionActive = false)).isFalse()
        assertThat(OtpCopyAffordance.visible("  ", selectionActive = false)).isFalse()
    }

    @Test
    fun `hidden while selection mode is active - even on an OTP message`() {
        assertThat(OtpCopyAffordance.visible("123456", selectionActive = true)).isFalse()
    }
}
