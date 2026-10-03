package app.clearsms.notification

import android.app.Notification
import android.content.Context
import androidx.core.app.NotificationCompat
import androidx.test.core.app.ApplicationProvider
import app.clearsms.data.db.MessageEntity
import app.clearsms.domain.categorizer.SenderIdLookup
import app.clearsms.domain.model.Category
import app.clearsms.shortcuts.ConversationShortcutRegistry
import app.clearsms.shortcuts.ConversationShortcutSelection
import app.clearsms.testing.FakeSettingsRepository
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Android 11 conversation notifications (the `setShortcutId` half of the
 * shortcut plumbing), with the invariant that matters most for an SMS app
 * pinned first: a notification is built the same, complete, way whether or
 * not its conversation shortcut exists. The shortcut id is attached ONLY
 * when the registry says the shortcut is published, so the platform's
 * "invalid shortcut" leniency is a backstop for a race, not the normal path.
 *
 * Nothing here touches ShortcutManager: the registry is the one seam, and
 * the test drives it directly.
 */
@RunWith(RobolectricTestRunner::class)
class ConversationNotificationTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val iconFactory = SenderIconFactory(context)

    private val rawResolver =
        object : NotificationSenderResolver(
            context,
            app.clearsms.sms.ContactsSource(context),
            SenderIdLookup { null },
        ) {
            override fun resolve(sender: String) = NotificationSender(name = sender, monogram = "X")
        }

    private val sectionGate = NotificationSectionGate(FakeSettingsRepository())
    private val mutedGate = MutedSenderGate(FakeSettingsRepository())

    private fun notifier(registry: ConversationShortcutRegistry) =
        MessageNotifier(context, rawResolver, iconFactory, sectionGate, mutedGate, registry)

    private val message =
        MessageEntity(
            id = 7L,
            threadId = 42L,
            sender = "+91 98765 43210",
            normalizedSender = "9876543210",
            body = "see you at nine",
            timestamp = 1_000L,
            category = Category.PERSONAL,
        )

    @Test
    fun `a published shortcut makes the notification name it and mirror it as the locus`() {
        val n = notifier { threadId -> threadId == 42L }.build(message)
        assertThat(n.shortcutId).isEqualTo(ConversationShortcutSelection.shortcutId(42L))
        assertThat(n.shortcutId).isEqualTo("thread:42")
        assertThat(n.locusId?.id).isEqualTo("thread:42")
        assertMessagingStyleIntact(n)
    }

    @Test
    fun `a missing shortcut costs nothing - the notification is complete and names no shortcut`() {
        // Setting off / outside the budget / excluded / rate-limited / API < 25
        // all look the same to the notifier: "not published".
        val n = notifier { false }.build(message)
        assertThat(n.shortcutId).isNull()
        assertThat(n.locusId).isNull()
        assertMessagingStyleIntact(n)
    }

    @Test
    fun `with and without a shortcut the notification is otherwise identical`() {
        val with = notifier { true }.build(message)
        val without = notifier { false }.build(message)
        // The shortcut is an addition, never a substitution: same channel,
        // same text, same tap target, same actions, same style.
        assertThat(without.channelId).isEqualTo(with.channelId)
        assertThat(without.extras.getCharSequence(Notification.EXTRA_TITLE))
            .isEqualTo(with.extras.getCharSequence(Notification.EXTRA_TITLE))
        assertThat(without.extras.getCharSequence(Notification.EXTRA_TEXT))
            .isEqualTo(with.extras.getCharSequence(Notification.EXTRA_TEXT))
        assertThat(without.extras.getString(Notification.EXTRA_TEMPLATE))
            .isEqualTo(with.extras.getString(Notification.EXTRA_TEMPLATE))
        assertThat(without.actions.orEmpty().map { it.title.toString() })
            .isEqualTo(with.actions.orEmpty().map { it.title.toString() })
        assertThat(without.contentIntent).isNotNull()
        assertThat(with.contentIntent).isNotNull()
    }

    @Test
    fun `the default registry reports nothing published`() {
        // The injected default (and the pre-feature baseline): no shortcut.
        val n = MessageNotifier(context, rawResolver, iconFactory, sectionGate, mutedGate).build(message)
        assertThat(n.shortcutId).isNull()
    }

    @Test
    fun `the registry read is a plain function call - no suspension, no system service`() {
        // Pinned by the interface shape: a non-suspending fun interface. A
        // suspend fun would not compile into the lambda above; the notifier's
        // build() is itself non-suspending and calls it inline.
        val registry = ConversationShortcutRegistry { it == 1L }
        assertThat(registry.isPublished(1L)).isTrue()
        assertThat(registry.isPublished(2L)).isFalse()
    }

    private fun assertMessagingStyleIntact(n: Notification) {
        assertThat(n.extras.getString(Notification.EXTRA_TEMPLATE))
            .isEqualTo(Notification.MessagingStyle::class.java.name)
        val restored = requireNotNull(NotificationCompat.MessagingStyle.extractMessagingStyleFromNotification(n))
        assertThat(restored.messages.single().text.toString()).isEqualTo("see you at nine")
        // Same Person key as the shortcut's Person: one identity to the system.
        assertThat(restored.messages.single().person?.key).isEqualTo("9876543210")
        assertThat(n.channelId).isEqualTo(Channels.MESSAGES)
        assertThat(n.contentIntent).isNotNull()
    }
}
