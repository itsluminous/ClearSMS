package app.clearsms.notification

import app.clearsms.domain.model.NotificationAction
import app.clearsms.sms.SenderRepliability
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
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
    fun `reply is suppressed when the sender is not repliable`() {
        val planned =
            NotificationActionPlanner.forMessage(
                selected = setOf(NotificationAction.MARK_READ, NotificationAction.REPLY),
                repliable = false,
            )
        assertThat(planned).containsExactly(NotificationAction.MARK_READ)
    }

    @Test
    fun `reply is offered exactly where the shared predicate says a reply can be addressed`() {
        // Numbers and numeric short codes (GitHub #75: "reply WEITER to
        // 80122") get REPLY; alphanumeric ids, which the platform cannot
        // encode as a destination, and non-addresses do not.
        listOf("+91 98765 43210", "9876543210", "+49 170 1234567", "80122", "56767", "139", "22000").forEach {
            assertWithMessage(it).that(NotificationActionPlanner.isRepliableAddress(it)).isTrue()
        }
        listOf("VM-HDFCBK", "AX-SWIGGY-S", "O2", "", "1", "22", "1234567890123456").forEach {
            assertWithMessage("'$it'").that(NotificationActionPlanner.isRepliableAddress(it)).isFalse()
        }
        // One rule, not a notification-side copy of it.
        listOf("80122", "VM-HDFCBK", "+919876543210", "", "7").forEach {
            assertWithMessage(it)
                .that(NotificationActionPlanner.isRepliableAddress(it))
                .isEqualTo(SenderRepliability.isAddressable(it))
        }
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
