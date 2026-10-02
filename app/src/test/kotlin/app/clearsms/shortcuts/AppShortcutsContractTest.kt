package app.clearsms.shortcuts

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Color
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import androidx.test.core.app.ApplicationProvider
import app.clearsms.ConversationDeepLink
import app.clearsms.IntentTriage
import app.clearsms.MainActivity
import app.clearsms.R
import app.clearsms.data.db.MessageEntity
import app.clearsms.data.db.ShortcutCandidateRow
import app.clearsms.domain.categorizer.SenderIdLookup
import app.clearsms.domain.model.Category
import app.clearsms.notification.MessageNotifier
import app.clearsms.notification.MutedSenderGate
import app.clearsms.notification.NotificationSectionGate
import app.clearsms.notification.NotificationSender
import app.clearsms.notification.NotificationSenderResolver
import app.clearsms.notification.SenderIconFactory
import app.clearsms.sms.ContactsSource
import app.clearsms.testing.FakeSettingsRepository
import app.clearsms.ui.navigation.LaterIntentAction
import app.clearsms.ui.navigation.LaterIntentTriage
import app.clearsms.ui.navigation.Routes
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.xmlpull.v1.XmlPullParser
import java.io.File

/**
 * Source and manifest contracts of the launcher shortcuts (issue #81):
 *
 * - the launcher activity declares `res/xml/shortcuts.xml` through the
 *   `android.app.shortcuts` meta-data, and that file holds exactly the one
 *   static shortcut the publisher subtracts from the system budget;
 * - the static "New message" shortcut targets MainActivity with the bare
 *   `smsto:` SENDTO the #32 composer path already handles - so it opens the
 *   composer EMPTY and never auto-sends;
 * - a conversation shortcut's intent IS the notification tap's intent
 *   ([ConversationDeepLink]): explicit MainActivity, same flags, a uri the
 *   sanitizer accepts and the later-intent triage turns into a plain
 *   conversation push (not a tab selection - so it can never corrupt the
 *   bottom bar's saved stack, and never a hand-rolled second mechanism);
 * - the shortcut carries the thread id, the inbox display name and the
 *   avatar - and nothing else;
 * - the stale KDoc claim in MessageNotifier ("No shortcut/bubble APIs are
 *   used") is gone;
 * - `allowBackup` stays false, so the system backs up none of the shortcut
 *   data it would otherwise copy (pinned + static) for this app.
 */
@RunWith(RobolectricTestRunner::class)
class AppShortcutsContractTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val iconFactory = SenderIconFactory(context)
    private val factory = ConversationShortcutFactory(context, iconFactory)

    private val row =
        ShortcutCandidateRow(
            threadId = 42L,
            sender = "+91 98765 43210",
            normalizedSender = "9876543210",
            timestamp = 1_000L,
            category = Category.PERSONAL,
            isBlockedSender = false,
            pinnedAt = null,
        )

    // region manifest + xml

    @Test
    fun `the launcher activity declares the shortcuts xml`() {
        val info =
            context.packageManager.getActivityInfo(
                ComponentName(context, MainActivity::class.java),
                PackageManager.GET_META_DATA,
            )
        assertThat(info.metaData.getInt("android.app.shortcuts")).isEqualTo(R.xml.shortcuts)
    }

    @Test
    fun `shortcuts xml holds exactly the static count the publisher subtracts from the budget`() {
        val parsed = parseShortcuts()
        assertThat(parsed).hasSize(ConversationShortcutPublisher.STATIC_SHORTCUT_COUNT)
        assertThat(parsed.map { it.id }).containsExactly("compose")
    }

    @Test
    fun `the static New message shortcut opens the composer empty through the sms-link path`() {
        val compose = parseShortcuts().single()
        assertThat(compose.enabled).isTrue()
        assertThat(compose.shortLabelRes).isEqualTo(R.string.shortcut_compose_short)
        assertThat(context.getString(compose.shortLabelRes)).isEqualTo("New message")
        assertThat(compose.iconRes).isEqualTo(R.drawable.ic_shortcut_compose)
        assertThat(compose.intentAction).isEqualTo(Intent.ACTION_SENDTO)
        assertThat(compose.intentData).isEqualTo("smsto:")
        assertThat(compose.targetPackage).isEqualTo(context.packageName)
        assertThat(compose.targetClass).isEqualTo(MainActivity::class.java.name)

        // The intent as the launcher fires it, through the real triage.
        val fired = Intent(compose.intentAction, android.net.Uri.parse(compose.intentData))
        val send = IntentTriage.extractSendIntent(fired)
        assertThat(send.explicitCompose).isTrue()
        assertThat(send.recipient).isNull()
        assertThat(send.body).isNull()
        val later = LaterIntentTriage.classify(fired)
        assertThat(later).isEqualTo(LaterIntentAction.OpenCompose(Routes.compose(), rejectedAttachment = false))
    }

    @Test
    fun `allowBackup stays false so the system never backs up shortcut data for this app`() {
        // Verified against the platform docs: with allowBackup the system
        // backs up pinned shortcuts (not dynamic ones, not icons) and
        // re-publishes static ones on restore. With it off, nothing.
        val flags = context.applicationInfo.flags
        assertThat(flags and android.content.pm.ApplicationInfo.FLAG_ALLOW_BACKUP).isEqualTo(0)
    }

    // endregion

    // region the conversation shortcut

    @Test
    fun `a conversation shortcut is keyed by the app thread id and labelled with the display name`() {
        val sender = NotificationSender(name = "Priya", monogram = "P", isContact = true)
        val shortcut = factory.build(row, sender, rank = 2)
        assertThat(shortcut.id).isEqualTo("thread:42")
        assertThat(shortcut.shortLabel).isEqualTo("Priya")
        assertThat(shortcut.longLabel).isEqualTo("Priya")
        assertThat(shortcut.rank).isEqualTo(2)
        assertThat(shortcut.icon).isNotNull()
        assertThat(shortcut.icon.type).isEqualTo(IconCompat.TYPE_ADAPTIVE_BITMAP)
        // Names and identity only: no extras, no categories, no Person.
        assertThat(shortcut.intent.extras).isNull()
        assertThat(shortcut.categories).isNull()
    }

    @Test
    fun `a conversation shortcut fires the notification tap's own deep link`() {
        val shortcut = factory.build(row, NotificationSender(name = "Priya", monogram = "P"), rank = 0)
        val intent = shortcut.intent
        assertThat(intent.action).isEqualTo(Intent.ACTION_VIEW)
        assertThat(intent.dataString).isEqualTo("clearsms://conversation/42")
        assertThat(intent.component).isEqualTo(ComponentName(context, MainActivity::class.java))
        assertThat(intent.flags and Intent.FLAG_ACTIVITY_NEW_TASK).isNotEqualTo(0)
        assertThat(intent.flags and Intent.FLAG_ACTIVITY_CLEAR_TOP).isNotEqualTo(0)

        // The sanitizer at MainActivity.onCreate / onNewIntent keeps it...
        assertThat(IntentTriage.sanitizeDeepLink(intent)).isSameInstanceAs(intent)
        // ...and the later-intent triage makes it a plain conversation push,
        // never a tab selection (the v0.17.2 saved-stack lesson applies to
        // tab routes only; a conversation is not one).
        val action = LaterIntentTriage.classify(intent)
        assertThat(action).isEqualTo(LaterIntentAction.Navigate(Routes.conversation(42L), selectTab = false))
    }

    @Test
    fun `shortcut and notification build the identical explicit intent`() {
        val message =
            MessageEntity(
                id = 7L,
                threadId = 42L,
                sender = "+91 98765 43210",
                normalizedSender = "9876543210",
                body = "hi",
                timestamp = 1_000L,
                category = Category.PERSONAL,
            )
        val rawResolver =
            object : NotificationSenderResolver(context, ContactsSource(context), SenderIdLookup { null }) {
                override fun resolve(sender: String) = NotificationSender(name = sender, monogram = "X")
            }
        val fake = FakeSettingsRepository()
        val notifier =
            MessageNotifier(context, rawResolver, iconFactory, NotificationSectionGate(fake), MutedSenderGate(fake))
        val tapped = shadowOf(notifier.build(message).contentIntent).savedIntent
        val fromShortcut = ConversationDeepLink.intent(context, 42L)
        assertThat(tapped.component).isEqualTo(fromShortcut.component)
        assertThat(tapped.action).isEqualTo(fromShortcut.action)
        assertThat(tapped.flags).isEqualTo(fromShortcut.flags)
        // Same uri shape; the notification adds the message to highlight.
        assertThat(tapped.data).isEqualTo(ConversationDeepLink.uri(42L, 7L))
        assertThat(IntentTriage.isValidDeepLink(fromShortcut.data!!)).isTrue()
        assertThat(IntentTriage.isValidDeepLink(tapped.data!!)).isTrue()
    }

    @Test
    fun `the shortcut icon is an adaptive square with the avatar inset on its plate`() {
        val avatar = SenderIconFactory.monogramBitmap("P", Color.RED, sizePx = 64)
        val plate = SenderIconFactory.adaptivePlate(avatar, Color.BLUE, sizePx = 108)
        assertThat(plate.width).isEqualTo(108)
        assertThat(plate.height).isEqualTo(108)
        // Corners lie outside every launcher mask and show the plate.
        assertThat(plate.getPixel(1, 1)).isEqualTo(Color.BLUE)
        assertThat(plate.getPixel(106, 106)).isEqualTo(Color.BLUE)
        // Inside the safe zone the avatar's red disc shows (off the monogram glyph).
        assertThat(plate.getPixel(54, 30)).isEqualTo(Color.RED)
        // The plate colour follows the avatar tier: tile colour for a
        // generated tile, white behind a contact photo.
        val tile = NotificationSender(name = "Acme", monogram = "A", colorArgb = Color.MAGENTA)
        assertThat(iconFactory.plateColorFor(tile)).isEqualTo(Color.MAGENTA)
        val plain = NotificationSender(name = "Priya", monogram = "P")
        assertThat(iconFactory.plateColorFor(plain)).isEqualTo(iconFactory.tileKeyFor(plain).colorArgb)
        assertThat(plate.config).isEqualTo(Bitmap.Config.ARGB_8888)
    }

    // endregion

    // region platform facts the publisher relies on

    @Test
    fun `the publisher degrades below API 25 and the compat budget is what it subtracts from`() {
        assertThat(ConversationShortcutPublisher.SHORTCUT_MANAGER_MIN_SDK).isEqualTo(25)
        // Robolectric's default SDK is >= 25: the budget comes from the
        // system service and the static shortcut is subtracted from it.
        val max = ShortcutManagerCompat.getMaxShortcutCountPerActivity(context)
        assertThat(max).isAtLeast(1)
        assertThat(ConversationShortcutSelection.budget(max, ConversationShortcutPublisher.STATIC_SHORTCUT_COUNT))
            .isEqualTo(max - 1)
        // start() must bail on the SDK gate BEFORE registering anything or
        // launching the collector (source contract: the gate is the first
        // statement of start()).
        val source = File("src/main/kotlin/app/clearsms/shortcuts/ConversationShortcutPublisher.kt").readText()
        val startBody = source.substringAfter("fun start() {").substringBefore("}")
        assertThat(
            startBody
                .trim()
                .lines()
                .first()
                .trim(),
        ).isEqualTo("if (Build.VERSION.SDK_INT < SHORTCUT_MANAGER_MIN_SDK) return")
    }

    @Test
    fun `MessageNotifier no longer claims that no shortcut APIs are used`() {
        val source = File("src/main/kotlin/app/clearsms/notification/MessageNotifier.kt").readText()
        assertWithMessage("KDoc must not lie about shortcut usage now that conversation shortcuts exist")
            .that(source)
            .doesNotContain("No shortcut/bubble APIs are used")
        assertThat(source).contains("ConversationShortcutPublisher")
    }

    @Test
    fun `every external conversation entry point builds its intent through ConversationDeepLink`() {
        val notifiers =
            listOf("MessageNotifier", "OtpNotifier", "TransactionNotifier").map {
                File("src/main/kotlin/app/clearsms/notification/$it.kt")
            } + File("src/main/kotlin/app/clearsms/shortcuts/ConversationShortcutFactory.kt")
        for (file in notifiers) {
            val source = file.readText()
            val codeLines =
                source.lines().filterNot { line ->
                    val trimmed = line.trimStart()
                    trimmed.startsWith("*") || trimmed.startsWith("//") || trimmed.startsWith("/*")
                }
            assertWithMessage("${file.name} must not hand-roll the conversation uri")
                .that(codeLines.filter { it.contains("\"clearsms://conversation/") })
                .isEmpty()
            assertWithMessage("${file.name} must use the shared deep link")
                .that(source)
                .contains("ConversationDeepLink")
        }
    }

    // endregion

    // region xml parsing

    private data class StaticShortcut(
        val id: String,
        val enabled: Boolean,
        val shortLabelRes: Int,
        val iconRes: Int,
        val intentAction: String?,
        val intentData: String?,
        val targetPackage: String?,
        val targetClass: String?,
    )

    private fun parseShortcuts(): List<StaticShortcut> {
        val parser = context.resources.getXml(R.xml.shortcuts)
        val result = mutableListOf<StaticShortcut>()
        var id: String? = null
        var enabled = true
        var shortLabel = 0
        var icon = 0
        var action: String? = null
        var data: String? = null
        var targetPackage: String? = null
        var targetClass: String? = null
        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            if (event == XmlPullParser.START_TAG) {
                when (parser.name) {
                    "shortcut" -> {
                        id = parser.getAttributeValue(ANDROID_NS, "shortcutId")
                        enabled = parser.getAttributeBooleanValue(ANDROID_NS, "enabled", true)
                        shortLabel = parser.getAttributeResourceValue(ANDROID_NS, "shortcutShortLabel", 0)
                        icon = parser.getAttributeResourceValue(ANDROID_NS, "icon", 0)
                    }

                    "intent" -> {
                        action = parser.getAttributeValue(ANDROID_NS, "action")
                        data = parser.getAttributeValue(ANDROID_NS, "data")
                        targetPackage = parser.getAttributeValue(ANDROID_NS, "targetPackage")
                        targetClass = parser.getAttributeValue(ANDROID_NS, "targetClass")
                    }
                }
            } else if (event == XmlPullParser.END_TAG && parser.name == "shortcut") {
                result += StaticShortcut(id!!, enabled, shortLabel, icon, action, data, targetPackage, targetClass)
            }
            event = parser.next()
        }
        return result
    }

    private companion object {
        const val ANDROID_NS = "http://schemas.android.com/apk/res/android"
    }

    // endregion
}
