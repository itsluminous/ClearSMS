package app.clearsms.notification

import android.app.NotificationManager
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import app.clearsms.data.db.MessageEntity
import app.clearsms.domain.categorizer.SenderIdLookup
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
 * Pinned behavior of the Spam notification channel: a SPAM-category message
 * posts on its own `spam` channel, which is created BLOCKED
 * (IMPORTANCE_NONE) like Promotions - spam notifications are noise by
 * definition, and the blocked channel is what lets a user switch them on in
 * system settings if they want them. A message that is ALSO scam-flagged
 * keeps its Security warning (the flag outranks the category in routing),
 * the Inbox section gate silences spam like every message notification, and
 * every other category still routes exactly where it did.
 */
@RunWith(RobolectricTestRunner::class)
class SpamRoutingTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val settings = FakeSettingsRepository()
    private val gate = NotificationSectionGate(settings)
    private val iconFactory = SenderIconFactory(context)

    private val rawResolver =
        object : NotificationSenderResolver(
            context,
            app.clearsms.sms.ContactsSource(context),
            SenderIdLookup { null },
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
        sender = "JUNKCO",
        normalizedSender = "JUNKCO",
        body = "Mega sale this weekend! Flat 70% off on everything.",
        timestamp = 1_000L,
        category = category,
        subCategory = subCategory,
        extractedOtp = otp,
    )

    @Test
    fun `a SPAM message notifies on the spam channel`() =
        runBlocking {
            router.route(message(1, Category.SPAM))
            val posted = shade().getNotification(NotificationIds.messageThread(1))
            assertThat(posted).isNotNull()
            assertThat(posted.channelId).isEqualTo(Channels.SPAM)
        }

    @Test
    fun `the spam channel is created BLOCKED - importance NONE, like promotions`() {
        Channels.ensureCreated(context)
        val manager = context.getSystemService(NotificationManager::class.java)
        val channel = manager.getNotificationChannel(Channels.SPAM)
        assertThat(channel).isNotNull()
        assertThat(channel.importance).isEqualTo(NotificationManager.IMPORTANCE_NONE)
        assertThat(channel.importance).isEqualTo(manager.getNotificationChannel(Channels.PROMOTIONS).importance)
        // ...and unlike Unknown senders, where blocked-by-default was the bug.
        assertThat(channel.importance).isNotEqualTo(manager.getNotificationChannel(Channels.UNKNOWN).importance)
    }

    @Test
    fun `the spam channel id is distinct from every other channel`() {
        assertThat(Channels.SPAM)
            .isNoneOf(
                Channels.MESSAGES,
                Channels.PROMOTIONS,
                Channels.UNKNOWN,
                Channels.OTP,
                Channels.TRANSACTIONS,
                Channels.SECURITY,
                Channels.SUMMARY,
                Channels.SYNC,
            )
    }

    @Test
    fun `a scam-FLAGGED spam message keeps its security warning`() =
        runBlocking {
            router.route(message(2, Category.SPAM, subCategory = SubCategory.SCAM))
            assertThat(shade().getNotification(NotificationIds.scam(2)).channelId).isEqualTo(Channels.SECURITY)
            assertThat(shade().getNotification(NotificationIds.messageThread(2))).isNull()
        }

    @Test
    fun `Inbox section off silences the spam notification`() =
        runBlocking {
            settings.enabledSections.value = EnabledSections(inbox = false, finance = true, alerts = true)
            router.route(message(3, Category.SPAM))
            assertThat(shade().size()).isEqualTo(0)

            settings.enabledSections.value = EnabledSections(inbox = true, finance = true, alerts = true)
            router.route(message(3, Category.SPAM))
            assertThat(shade().size()).isEqualTo(1)
        }

    @Test
    fun `every other category still routes exactly where it did`() =
        runBlocking {
            router.route(message(4, Category.PERSONAL))
            assertThat(shade().getNotification(NotificationIds.messageThread(4)).channelId).isEqualTo(Channels.MESSAGES)

            router.route(message(5, Category.IMPORTANT))
            assertThat(shade().getNotification(NotificationIds.messageThread(5)).channelId).isEqualTo(Channels.MESSAGES)

            router.route(message(6, Category.PROMOTIONAL))
            assertThat(shade().getNotification(NotificationIds.messageThread(6)).channelId).isEqualTo(Channels.PROMOTIONS)

            router.route(message(7, Category.UNKNOWN))
            assertThat(shade().getNotification(NotificationIds.messageThread(7)).channelId).isEqualTo(Channels.UNKNOWN)

            router.route(message(8, Category.OTP, otp = "123456"))
            assertThat(shade().getNotification(NotificationIds.otp(8))).isNotNull()

            router.route(message(9, Category.IMPORTANT, subCategory = SubCategory.SCAM))
            assertThat(shade().getNotification(NotificationIds.scam(9)).channelId).isEqualTo(Channels.SECURITY)
        }
}
