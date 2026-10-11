package app.clearsms.notification

import app.clearsms.domain.model.NotificationAction
import app.clearsms.sms.SenderRepliability
import app.clearsms.ui.components.DialableNumber
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
                dialable = true,
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
                dialable = true,
            )
        assertThat(planned).containsExactly(NotificationAction.DELETE)
    }

    @Test
    fun `reply is suppressed when the sender is not repliable`() {
        val planned =
            NotificationActionPlanner.forMessage(
                selected = setOf(NotificationAction.MARK_READ, NotificationAction.REPLY),
                repliable = false,
                dialable = false,
            )
        assertThat(planned).containsExactly(NotificationAction.MARK_READ)
    }

    @Test
    fun `call is offered for a dialable sender and sits right after reply`() {
        // GitHub #99. Declaration order puts CALL beside REPLY (the two
        // "respond to this person" buttons) and after the housekeeping pair.
        val planned =
            NotificationActionPlanner.forMessage(
                selected = setOf(NotificationAction.MARK_READ, NotificationAction.REPLY, NotificationAction.CALL),
                repliable = true,
                dialable = true,
            )
        assertThat(planned)
            .containsExactly(NotificationAction.MARK_READ, NotificationAction.REPLY, NotificationAction.CALL)
            .inOrder()
    }

    @Test
    fun `call is absent - not present and failing - when the sender is not dialable`() {
        // A short code can take an SMS reply yet is not a phone number:
        // REPLY stays, CALL goes. The notification must shrink to the
        // actions that work, never show a dead button (the #78 lesson).
        val planned =
            NotificationActionPlanner.forMessage(
                selected = setOf(NotificationAction.MARK_READ, NotificationAction.REPLY, NotificationAction.CALL),
                repliable = true,
                dialable = false,
            )
        assertThat(planned).containsExactly(NotificationAction.MARK_READ, NotificationAction.REPLY).inOrder()
    }

    @Test
    fun `dialability follows the shared DialableNumber rule exactly`() {
        // Subscriber numbers dial; short codes ("139", "56767", "80122") and
        // alphanumeric TRAI ids ("VM-HDFCBK") do not - even though several
        // of those short codes ARE repliable. Same inputs the title-bar
        // button is judged by.
        listOf("+91 98765 43210", "9876543210", "+49 170 1234567", "18001234567").forEach {
            assertWithMessage(it).that(NotificationActionPlanner.dialableAddress(it)).isNotNull()
        }
        listOf("139", "56767", "80122", "22000", "VM-HDFCBK", "AX-SWIGGY-S", "O2", "", "1234567890123456").forEach {
            assertWithMessage("'$it'").that(NotificationActionPlanner.dialableAddress(it)).isNull()
        }
        // One rule, not a notification-side copy of it.
        listOf("80122", "VM-HDFCBK", "+919876543210", "9876543210", "", "7", "98765-43210").forEach {
            assertWithMessage(it)
                .that(NotificationActionPlanner.dialableAddress(it))
                .isEqualTo(DialableNumber.of(it))
        }
    }

    @Test
    fun `a stored selection beyond three is trimmed from the end in declaration order`() {
        // The Settings picker refuses a fourth tick, so this only happens to
        // a hand-edited or restored selection. The cap then drops the
        // LAST-declared choices: with the default trio plus CALL, CALL is the
        // one that does not fit; when REPLY is withheld for a non-repliable
        // sender, CALL moves up into the freed slot rather than vanishing.
        val four =
            setOf(
                NotificationAction.MARK_READ,
                NotificationAction.DELETE,
                NotificationAction.REPLY,
                NotificationAction.CALL,
            )
        assertThat(NotificationActionPlanner.forMessage(four, repliable = true, dialable = true))
            .containsExactly(NotificationAction.MARK_READ, NotificationAction.DELETE, NotificationAction.REPLY)
            .inOrder()
        assertThat(NotificationActionPlanner.forMessage(four, repliable = false, dialable = true))
            .containsExactly(NotificationAction.MARK_READ, NotificationAction.DELETE, NotificationAction.CALL)
            .inOrder()
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
    fun `otp notifications never offer call`() {
        // An OTP is a code to copy from a service, not a person to phone
        // back; CALL is withheld like REPLY regardless of the selection.
        val planned =
            NotificationActionPlanner.forOtp(
                selected = setOf(NotificationAction.CALL, NotificationAction.MARK_READ),
            )
        assertThat(planned).containsExactly(NotificationAction.COPY_OTP, NotificationAction.MARK_READ).inOrder()
    }

    @Test
    fun `otp actions cap at three when everything is selected`() {
        val planned = NotificationActionPlanner.forOtp(NotificationAction.entries.toSet())
        assertThat(planned.size).isEqualTo(NotificationActionPlanner.MAX_ACTIONS)
        assertThat(planned.first()).isEqualTo(NotificationAction.COPY_OTP)
    }
}
