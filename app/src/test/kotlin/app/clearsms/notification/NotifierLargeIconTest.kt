package app.clearsms.notification

import android.app.NotificationManager
import android.content.Context
import androidx.core.app.NotificationCompat
import androidx.test.core.app.ApplicationProvider
import app.clearsms.data.db.MessageEntity
import app.clearsms.domain.model.Category
import app.clearsms.domain.model.OtpDisplaySize
import app.clearsms.ui.components.BrandCategory
import com.google.common.truth.Truth.assertThat
import kotlinx.serialization.json.Json
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

/**
 * Every notification that identifies a sender must carry the sender's
 * identity in the large-icon slot (or the MessagingStyle Person icon),
 * resolved through the shared avatar chain. The small icon stays the app's
 * monochrome mark — Android requires it — so the brand goes here.
 */
@RunWith(RobolectricTestRunner::class)
class NotifierLargeIconTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val iconFactory = SenderIconFactory(context)

    /** Stub resolver: a curated bank brand with bundled artwork. */
    private val brandResolver =
        object : NotificationSenderResolver(
            context,
            app.clearsms.sms.ContactsSource(context),
            app.clearsms.data.senderid
                .SenderIdStore(context),
        ) {
            override fun resolve(sender: String) =
                NotificationSender(
                    name = "HDFC Bank",
                    monogram = "H",
                    colorArgb = 0xFF004C8F.toInt(),
                    brandKey = "hdfc",
                    brandCategory = BrandCategory.BANK,
                )
        }

    private val message =
        MessageEntity(
            id = 9L,
            threadId = 2L,
            sender = "AX-HDFCBK",
            normalizedSender = "HDFCBK",
            body = "Rs.500 debited from a/c **2863. Avl bal Rs.1,000",
            timestamp = 1_000L,
            category = Category.IMPORTANT,
        )

    private fun postedNotifications() = shadowOf(context.getSystemService(NotificationManager::class.java)).allNotifications

    @Test
    fun `message notification person carries the resolved icon`() {
        MessageNotifier(context, brandResolver, iconFactory).notify(message)
        val posted = postedNotifications().single()
        val style = NotificationCompat.MessagingStyle.extractMessagingStyleFromNotification(posted)
        assertThat(style).isNotNull()
        assertThat(
            style!!
                .messages
                .single()
                .person
                ?.icon,
        ).isNotNull()
    }

    @Test
    fun `scam warning attaches the sender's large icon`() {
        MessageNotifier(context, brandResolver, iconFactory).notifyScam(message.copy(id = 11L))
        val posted = postedNotifications().single()
        assertThat(posted.getLargeIcon()).isNotNull()
    }

    @Test
    fun `transaction notification attaches the bank's large icon`() {
        val notification =
            TransactionNotifier(context, Json, brandResolver, iconFactory)
                .buildNotification(
                    message.copy(extractedDataJson = """{"amount":"500.0","type":"debit","bank":"HDFC Bank"}"""),
                    MessageNotifier.DEFAULT_SELECTED,
                )
        assertThat(notification).isNotNull()
        assertThat(notification!!.getLargeIcon()).isNotNull()
    }

    @Test
    fun `otp notification attaches the large icon on the private and public versions`() {
        val notification =
            OtpNotifier(context, brandResolver, iconFactory)
                .build(
                    message.copy(category = Category.OTP, extractedOtp = "123456"),
                    "123456",
                    OtpDisplaySize.DEFAULT,
                    MessageNotifier.DEFAULT_SELECTED,
                )
        assertThat(notification.getLargeIcon()).isNotNull()
        // Lockscreen privacy is intact: private visibility, digit-free public.
        assertThat(notification.visibility).isEqualTo(NotificationCompat.VISIBILITY_PRIVATE)
        val public = notification.publicVersion
        assertThat(public.getLargeIcon()).isNotNull()
        val publicTitle =
            public.extras
                .getCharSequence(NotificationCompat.EXTRA_TITLE)
                ?.toString()
                .orEmpty()
        assertThat(publicTitle.any { it.isDigit() }).isFalse()
    }
}
