package app.clearsms.notification

import app.clearsms.domain.model.NotificationAction
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class NotificationActionPlannerTest {
    @Test
    fun `message actions honor selection in declaration order and cap at three`() {
        val planned =
            NotificationActionPlanner.forMessage(
                selected = NotificationAction.entries.toSet(),
                repliable = true,
            )
        assertThat(planned)
            .containsExactly(
                NotificationAction.MARK_READ,
                NotificationAction.DELETE,
                NotificationAction.REPLY,
            ).inOrder()
        assertThat(planned.size).isAtMost(NotificationActionPlanner.MAX_ACTIONS)
    }

    @Test
    fun `unselected actions are not offered`() {
        val planned =
            NotificationActionPlanner.forMessage(
                selected = setOf(NotificationAction.DELETE),
                repliable = true,
            )
        assertThat(planned).containsExactly(NotificationAction.DELETE)
    }

    @Test
    fun `reply is suppressed for short-code and alphanumeric senders`() {
        val planned =
            NotificationActionPlanner.forMessage(
                selected = setOf(NotificationAction.MARK_READ, NotificationAction.REPLY),
                repliable = false,
            )
        assertThat(planned).containsExactly(NotificationAction.MARK_READ)
    }

    @Test
    fun `repliable addresses are phone numbers not sender ids`() {
        assertThat(NotificationActionPlanner.isRepliableAddress("+91 98765 43210")).isTrue()
        assertThat(NotificationActionPlanner.isRepliableAddress("9876543210")).isTrue()
        // Alphanumeric sender ids and short codes are one-way routes.
        assertThat(NotificationActionPlanner.isRepliableAddress("VM-HDFCBK")).isFalse()
        assertThat(NotificationActionPlanner.isRepliableAddress("56767")).isFalse()
        assertThat(NotificationActionPlanner.isRepliableAddress("AX-SWIGGY-S")).isFalse()
    }

    @Test
    fun `otp actions always lead with copy and honor the selection`() {
        val defaults =
            NotificationActionPlanner.forOtp(
                selected = setOf(NotificationAction.MARK_READ, NotificationAction.REPLY),
            )
        // Copy is always first even when not selected; REPLY never applies.
        assertThat(defaults)
            .containsExactly(NotificationAction.COPY_OTP, NotificationAction.MARK_READ)
            .inOrder()

        val shareAndDelete =
            NotificationActionPlanner.forOtp(
                selected = setOf(NotificationAction.SHARE_OTP, NotificationAction.DELETE),
            )
        assertThat(shareAndDelete)
            .containsExactly(
                NotificationAction.COPY_OTP,
                NotificationAction.DELETE,
                NotificationAction.SHARE_OTP,
            ).inOrder()
    }

    @Test
    fun `otp actions cap at three when everything is selected`() {
        val planned = NotificationActionPlanner.forOtp(NotificationAction.entries.toSet())
        assertThat(planned.size).isEqualTo(NotificationActionPlanner.MAX_ACTIONS)
        assertThat(planned.first()).isEqualTo(NotificationAction.COPY_OTP)
    }
}
