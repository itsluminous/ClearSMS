package app.clearsms.notification

import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import app.clearsms.R
import app.clearsms.data.db.MessageEntity
import app.clearsms.domain.model.Category
import app.clearsms.domain.model.NotificationAction
import app.clearsms.ui.settings.DirectCall
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
 * By default the button opens the dialer PREFILLED and stops, exactly like
 * the conversation title bar's call button - the maintainer declined
 * one-click calling. The opt-in "Direct call" setting (default OFF) lets a
 * tap place the call instead, which needs the CALL_PHONE permission. Four
 * things must therefore hold, and each is pinned here because each is
 * invisible in review once broken:
 *
 * 1. the action's PendingIntent targets [CallActionActivity] with the
 *    sender's number, which decides AT TAP TIME between ACTION_DIAL and
 *    ACTION_CALL through [DirectCall] - so a toggle flipped or a permission
 *    revoked after posting still ends in the dialer, never a dead button;
 * 2. the manifest carries CALL_PHONE (it cannot be declared conditionally,
 *    so every install lists it even though it is requested only when the
 *    toggle is switched on) and still NO CALL_PRIVILEGED, and ACTION_CALL
 *    is reached from exactly one place in main code - [DirectCall]'s
 *    direct-call branch;
 * 3. the notification and the title bar judge dialability by the SAME
 *    predicate, [app.clearsms.ui.components.DialableNumber], and fire the
 *    SAME mapping, [DirectCall] - so a short code or alphanumeric sender id
 *    gets no button on either surface, and both surfaces do the same thing
 *    on tap;
 * 4. the default is OFF: with no preference stored the mapping is
 *    ACTION_DIAL, so nobody who did not opt in sees a change.
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
    fun `call action hands the sender's number to CallActionActivity, which decides at tap time`() {
        val action = callActions("+919876543210").single()
        assertThat(action.title.toString()).isEqualTo(context.getString(R.string.notification_action_call))
        val intent = shadowOf(action.actionIntent).savedIntent
        // An explicit intent to our own invisible activity - NOT a direct
        // ACTION_DIAL or ACTION_CALL baked in when the notification was
        // built: the dial-or-call decision is made when the user taps.
        assertThat(intent.component?.className).isEqualTo(CallActionActivity::class.java.name)
        assertThat(intent.component?.packageName).isEqualTo(context.packageName)
        assertThat(intent.getStringExtra(CallActionActivity.EXTRA_NUMBER)).isEqualTo("+919876543210")
        assertThat(intent.action).isNotEqualTo(Intent.ACTION_CALL)
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
    fun `manifest declares CALL_PHONE for the opt-in direct call, no CALL_PRIVILEGED, and ACTION_CALL lives in DirectCall only`() {
        val manifest = File("src/main/AndroidManifest.xml").readText()
        // The honest cost of the opt-in: CALL_PHONE cannot be declared
        // conditionally, so it is in the manifest for every install - and
        // the declaration says so, next to the permission.
        val declared =
            Regex("<uses-permission\\s+android:name=\"([^\"]+)\"")
                .findAll(manifest)
                .map { it.groupValues[1] }
                .toList()
        assertThat(declared).contains("android.permission.CALL_PHONE")
        assertThat(declared).doesNotContain("android.permission.CALL_PRIVILEGED")
        assertThat(declared.filter { "CALL" in it }).containsExactly("android.permission.CALL_PHONE")
        // The tap target is declared, private, and leaves no trace.
        val activity = manifest.substringAfter(".notification.CallActionActivity").substringBefore("/>")
        assertThat(activity).contains("""android:exported="false"""")
        assertThat(activity).contains("""android:noHistory="true"""")
        assertThat(activity).contains("""android:excludeFromRecents="true"""")
        // Exactly ONE main source file reaches for the one intent that needs
        // the permission - DirectCall, on its opted-in-and-permitted branch
        // (comments may name it to say so). Every other call path is
        // ACTION_DIAL / VIEW / INSERT, as before.
        val offenders =
            File("src/main/kotlin")
                .walk()
                .filter { it.extension == "kt" }
                .filter { it.readText().contains(Regex("""Intent\.ACTION_CALL\b""")) }
                .map { it.path }
                .toList()
        assertThat(offenders).containsExactly("src/main/kotlin/app/clearsms/ui/settings/DirectCall.kt")
    }

    @Test
    fun `default is the dialer - direct call is OFF unless the user opted in AND the permission is granted`() {
        // No preference stored → false (SettingsRepositoryImpl); and the
        // mapping is ACTION_DIAL for everything but opted-in-and-permitted.
        assertThat(File("src/main/kotlin/app/clearsms/data/prefs/SettingsRepositoryImpl.kt").readText())
            .contains("it[KEY_DIRECT_CALL] ?: false")
        val dial = DirectCall.intent("+919876543210", directCall = false, permitted = true)
        assertThat(dial.action).isEqualTo(Intent.ACTION_DIAL)
        assertThat(dial.data.toString()).isEqualTo("tel:+919876543210")
        // Opted in but the grant is missing or revoked: the dialer, never nothing.
        val revoked = DirectCall.intent("+919876543210", directCall = true, permitted = false)
        assertThat(revoked.action).isEqualTo(Intent.ACTION_DIAL)
        // Only both together place the call.
        val direct = DirectCall.intent("+919876543210", directCall = true, permitted = true)
        assertThat(direct.action).isEqualTo(Intent.ACTION_CALL)
        assertThat(direct.data.toString()).isEqualTo("tel:+919876543210")
        // Robolectric grants nothing by default: the live check says so.
        assertThat(DirectCall.isPermitted(context)).isFalse()
    }

    @Test
    fun `notification and title bar share one dialability predicate and one call mapping`() {
        val planner = File("src/main/kotlin/app/clearsms/notification/NotificationActionPlanner.kt").readText()
        val factory = File("src/main/kotlin/app/clearsms/notification/MessageActionFactory.kt").readText()
        val activity = File("src/main/kotlin/app/clearsms/notification/CallActionActivity.kt").readText()
        val screen = File("src/main/kotlin/app/clearsms/ui/conversation/ConversationScreen.kt").readText()

        // Both surfaces ask DialableNumber, neither re-implements it - and
        // the tap target re-gates the number through the same rule.
        assertThat(planner).contains("DialableNumber.of(sender)")
        assertThat(screen).contains("DialableNumber.of(state.address)")
        assertThat(factory).contains("NotificationActionPlanner.dialableAddress(message.sender)")
        assertThat(activity).contains("NotificationActionPlanner::dialableAddress")

        // Both fire through DirectCall.place with the SAME preference, which
        // maps to ACTION_DIAL by default and ACTION_CALL only when opted in
        // and permitted (pinned above). Neither builds a tel: intent itself.
        assertThat(factory).contains("CallActionActivity.intent(context, number)")
        assertThat(activity).contains("DirectCall.place(this@CallActionActivity, number, settings.directCall.first())")
        assertThat(screen).contains("DirectCall.place(context, dialableSender, state.directCall)")
        assertThat(factory).doesNotContain("\"tel:")
        assertThat(screen).doesNotContain("openLink(\"tel:")
    }
}
