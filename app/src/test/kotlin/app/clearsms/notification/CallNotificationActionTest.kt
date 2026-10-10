package app.clearsms.notification

import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import app.clearsms.R
import app.clearsms.data.db.MessageEntity
import app.clearsms.domain.model.Category
import app.clearsms.domain.model.NotificationAction
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import java.io.File

/**
 * The notification's Call button (GitHub #99) and the contract behind it.
 *
 * The maintainer declined one-click calling: the button opens the dialer
 * PREFILLED and stops, exactly like the conversation title bar's call
 * button. Three things must therefore hold, and each is pinned here because
 * each is invisible in review once broken:
 *
 * 1. the PendingIntent is ACTION_DIAL on a `tel:` URI - never ACTION_CALL;
 * 2. the manifest carries no CALL_PHONE (the app's whole character is
 *    minimal permissions; ACTION_DIAL needs none);
 * 3. the notification and the title bar judge dialability by the SAME
 *    predicate, [app.clearsms.ui.components.DialableNumber], and fire the
 *    SAME `tel:` mapping, [app.clearsms.ui.settings.ExternalLinks] - so a
 *    short code or alphanumeric sender id gets no button on either surface.
 */
@RunWith(RobolectricTestRunner::class)
class CallNotificationActionTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    private fun message(sender: String) =
        MessageEntity(
            id = 42L,
            threadId = 7L,
            sender = sender,
            normalizedSender = sender,
            body = "call me when you can",
            timestamp = 1_000L,
            category = Category.PERSONAL,
        )

    private fun callActions(sender: String) =
        MessageActionFactory.build(context, message(sender), notificationId = 1, actions = listOf(NotificationAction.CALL))

    @Test
    fun `call action dials the sender prefilled and never places the call`() {
        val action = callActions("+919876543210").single()
        assertThat(action.title.toString()).isEqualTo(context.getString(R.string.notification_action_call))
        val intent = shadowOf(action.actionIntent).savedIntent
        assertThat(intent.action).isEqualTo(Intent.ACTION_DIAL)
        assertThat(intent.action).isNotEqualTo(Intent.ACTION_CALL)
        assertThat(intent.data.toString()).isEqualTo("tel:+919876543210")
        // Launched from the notification shade, outside any task of ours.
        assertThat(intent.flags and Intent.FLAG_ACTIVITY_NEW_TASK).isNotEqualTo(0)
        // No RemoteInput, no mutability: an immutable hand-off like SHARE.
        assertThat(action.remoteInputs).isNull()
    }

    @Test
    fun `a short code or alphanumeric sender id gets no call button at all`() {
        // The planner withholds CALL for these; the factory is a second gate
        // so no caller can produce a present-and-failing button.
        listOf("139", "56767", "80122", "VM-HDFCBK", "AX-SWIGGY-S", "").forEach { sender ->
            assertWithMessage("'$sender'").that(callActions(sender)).isEmpty()
            assertWithMessage("'$sender'")
                .that(
                    NotificationActionPlanner.forMessage(
                        selected = setOf(NotificationAction.CALL),
                        repliable = NotificationActionPlanner.isRepliableAddress(sender),
                        dialable = NotificationActionPlanner.dialableAddress(sender) != null,
                    ),
                ).isEmpty()
        }
    }

    @Test
    fun `manifest declares no CALL_PHONE permission`() {
        val manifest = File("src/main/AndroidManifest.xml").readText()
        assertThat(manifest).doesNotContain("CALL_PHONE")
        assertThat(manifest).doesNotContain("CALL_PRIVILEGED")
        // And no main source CODE reaches for the one intent that would need
        // it (comments may name it to say so).
        val offenders =
            File("src/main/kotlin")
                .walk()
                .filter { it.extension == "kt" }
                .filter { it.readText().contains(Regex("""Intent\.ACTION_CALL\b""")) }
                .map { it.path }
                .toList()
        assertThat(offenders).isEmpty()
    }

    @Test
    fun `notification and title bar share one dialability predicate and one tel mapping`() {
        val planner = File("src/main/kotlin/app/clearsms/notification/NotificationActionPlanner.kt").readText()
        val factory = File("src/main/kotlin/app/clearsms/notification/MessageActionFactory.kt").readText()
        val screen = File("src/main/kotlin/app/clearsms/ui/conversation/ConversationScreen.kt").readText()

        // Both surfaces ask DialableNumber, neither re-implements it.
        assertThat(planner).contains("DialableNumber.of(sender)")
        assertThat(screen).contains("DialableNumber.of(state.address)")
        assertThat(factory).contains("NotificationActionPlanner.dialableAddress(message.sender)")

        // Both fire a tel: URI through ExternalLinks, whose tel mapping is
        // ACTION_DIAL (pinned by ExternalLinksTest).
        assertThat(factory).contains("ExternalLinks")
        assertThat(factory).contains("\"tel:\$number\"")
        assertThat(screen).contains("openLink(\"tel:\$dialableSender\")")
        assertThat(screen).contains("ExternalLinks.open(context, url)")
    }
}
