package app.clearsms.notification

import android.app.NotificationManager
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import app.clearsms.data.db.MessageEntity
import app.clearsms.domain.model.Category
import app.clearsms.domain.model.EnabledSections
import app.clearsms.domain.model.SubCategory
import app.clearsms.testing.FakeSettingsRepository
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

/**
 * Pinned behavior: an UNKNOWN-category message notifies on its own
 * `unknown_senders` channel - a real person texting from a number that is
 * not in contacts must not arrive silently (the pre-fix `else -> Unit`
 * swallowed it). The channel is created UNBLOCKED at IMPORTANCE_DEFAULT,
 * unlike promotions whose blocked channel is what makes promos silent by
 * design. Every other category's routing stays byte-for-byte the same, the
 * Inbox section gate still silences it, and the notification reuses the
 * per-thread message id band so it can never collide with another
 * notifier's id or PendingIntent request code.
 */
@RunWith(RobolectricTestRunner::class)
class UnknownSenderRoutingTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val settings = FakeSettingsRepository()
    private val gate = NotificationSectionGate(settings)
    private val iconFactory = SenderIconFactory(context)

    private val rawResolver =
        object : NotificationSenderResolver(
            context,
            app.clearsms.sms.ContactsSource(context),
            app.clearsms.data.senderid
                .SenderIdStore(context),
        ) {
            override fun resolve(sender: String) = NotificationSender(name = sender, monogram = "X")
        }

    private val messageNotifier = MessageNotifier(context, rawResolver, iconFactory, gate)
    private val otpNotifier = OtpNotifier(context, rawResolver, iconFactory, gate)
    private val transactionNotifier =
        TransactionNotifier(context, Json { ignoreUnknownKeys = true }, rawResolver, iconFactory, gate)

    private val router =
        IncomingMessageRouter(
            context = context,
            settingsRepository = settings,
            otpNotifier = otpNotifier,
            messageNotifier = messageNotifier,
            transactionNotifier = transactionNotifier,
            applicationScope = CoroutineScope(Dispatchers.Unconfined + SupervisorJob()),
        )

    private fun shade() = shadowOf(context.getSystemService(NotificationManager::class.java))

    private fun message(
        id: Long,
        category: Category,
        subCategory: SubCategory? = null,
        otp: String? = null,
    ) = MessageEntity(
        id = id,
        threadId = id,
        sender = "9998887776",
        normalizedSender = "9998887776",
        body = "hello, this matched no rule",
        timestamp = 1_000L,
        category = category,
        subCategory = subCategory,
        extractedOtp = otp,
    )

    @Test
    fun `an UNKNOWN message notifies on the unknown_senders channel`() =
        runBlocking {
            router.route(message(1, Category.UNKNOWN))
            val posted = shade().getNotification(NotificationIds.messageThread(1))
            assertThat(posted).isNotNull()
            assertThat(posted.channelId).isEqualTo(Channels.UNKNOWN)
        }

    @Test
    fun `the unknown channel is created UNBLOCKED at DEFAULT importance`() {
        Channels.ensureCreated(context)
        val channel =
            context
                .getSystemService(NotificationManager::class.java)
                .getNotificationChannel(Channels.UNKNOWN)
        assertThat(channel).isNotNull()
        assertThat(channel.importance).isEqualTo(NotificationManager.IMPORTANCE_DEFAULT)
        assertThat(channel.importance).isNotEqualTo(NotificationManager.IMPORTANCE_NONE)
    }

    @Test
    fun `the unknown channel id is distinct from every other channel`() {
        assertThat(Channels.UNKNOWN)
            .isNoneOf(Channels.MESSAGES, Channels.PROMOTIONS, Channels.OTP, Channels.TRANSACTIONS, Channels.SECURITY)
    }

    @Test
    fun `every other category still routes exactly where it did`() =
        runBlocking {
            router.route(message(2, Category.PERSONAL))
            assertThat(shade().getNotification(NotificationIds.messageThread(2)).channelId)
                .isEqualTo(Channels.MESSAGES)

            router.route(message(3, Category.IMPORTANT))
            assertThat(shade().getNotification(NotificationIds.messageThread(3)).channelId)
                .isEqualTo(Channels.MESSAGES)

            router.route(message(4, Category.PROMOTIONAL))
            assertThat(shade().getNotification(NotificationIds.messageThread(4)).channelId)
                .isEqualTo(Channels.PROMOTIONS)

            router.route(message(5, Category.OTP, otp = "123456"))
            assertThat(shade().getNotification(NotificationIds.otp(5))).isNotNull()

            router.route(message(6, Category.UNKNOWN, subCategory = SubCategory.SCAM))
            assertThat(shade().getNotification(NotificationIds.scam(6)).channelId)
                .isEqualTo(Channels.SECURITY)
        }

    @Test
    fun `Inbox section off silences the unknown notification`() =
        runBlocking {
            settings.enabledSections.value = EnabledSections(inbox = false, finance = true, alerts = true)
            router.route(message(7, Category.UNKNOWN))
            assertThat(shade().size()).isEqualTo(0)

            settings.enabledSections.value = EnabledSections(inbox = true, finance = true, alerts = true)
            router.route(message(7, Category.UNKNOWN))
            assertThat(shade().size()).isEqualTo(1)
        }

    @Test
    fun `unknown reuses the message-thread id band - no collision with other notifiers`() =
        runBlocking {
            router.route(message(8, Category.UNKNOWN))
            val id = NotificationIds.messageThread(8)
            assertThat(shade().getNotification(id)).isNotNull()
            // Same message id in every other notifier's band maps elsewhere:
            // the bands are disjoint, so ids double safely as request codes.
            assertThat(id).isNotEqualTo(NotificationIds.otp(8))
            assertThat(id).isNotEqualTo(NotificationIds.transaction(8))
            assertThat(id).isNotEqualTo(NotificationIds.scam(8))
            assertThat(id).isNotEqualTo(NotificationIds.SEND_FAILURE)
            // The tap intent's request code is the thread id itself, and it
            // deep-links with ?messageId= so the tap highlights the message.
            val tap = shadowOf(shade().getNotification(id).contentIntent)
            assertThat(tap.requestCode).isEqualTo(id)
            assertThat(tap.savedIntent.data.toString()).isEqualTo("clearsms://conversation/8?messageId=8")
        }
}
