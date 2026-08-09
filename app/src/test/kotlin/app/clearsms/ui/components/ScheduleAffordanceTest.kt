package app.clearsms.ui.components

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * The clock badge on the Send button (the visual hint that Send carries a
 * long-press) appears exactly when the compose field has something to send.
 */
class ScheduleAffordanceTest {
    @Test
    fun `visible while the draft has content`() {
        assertThat(scheduleHintVisible("see you at nine")).isTrue()
    }

    @Test
    fun `hidden for an empty draft`() {
        assertThat(scheduleHintVisible("")).isFalse()
    }

    @Test
    fun `hidden for a whitespace-only draft`() {
        assertThat(scheduleHintVisible("   \n")).isFalse()
    }
}
