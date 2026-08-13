package app.clearsms.ui.components

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * The clock badge on the Send button (the visual hint that Send carries a
 * long-press) appears exactly when the compose field has something to send
 * AND no attachments are staged - scheduling is SMS-only this wave, so a
 * message with attachments loses the schedule affordance (the long-press
 * explains why instead).
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

    @Test
    fun `hidden when attachments are staged - scheduling is SMS-only`() {
        assertThat(scheduleHintVisible("see you at nine", attachmentCount = 1)).isFalse()
    }

    @Test
    fun `restored once attachments are removed`() {
        assertThat(scheduleHintVisible("see you at nine", attachmentCount = 0)).isTrue()
    }
}
