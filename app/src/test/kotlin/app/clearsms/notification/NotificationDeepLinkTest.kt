package app.clearsms.notification

import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import app.clearsms.data.db.MessageEntity
import app.clearsms.domain.model.Category
import app.clearsms.domain.model.OtpDisplaySize
import app.clearsms.domain.model.SubCategory
import com.google.common.truth.Truth.assertThat
import kotlinx.serialization.json.Json
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

/**
 * A notification about a SPECIFIC message must deep-link with that message's
 * id (`clearsms://conversation/{threadId}?messageId={id}`) so the tap
 * scrolls to and highlights it - the plain message, scam, OTP and
 * transaction notifications alike.
 *
 * And because every content intent uses FLAG_UPDATE_CURRENT, the request
 * codes across notification KINDS for the same thread/message must stay
 * pairwise distinct: two live notifications sharing a request code would
 * collapse onto one PendingIntent and send both taps to whichever message
 * was posted last. Each notifier borrows its own disjoint [NotificationIds]
 * band (the failure intent its documented `or 0x40000000` space), which
 * this test pins.
 */
@RunWith(RobolectricTestRunner::class)
class NotificationDeepLinkTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val iconFactory = SenderIconFactory(context)

    /** Stub resolver: raw sender name, so no contacts/directory access is needed. */
    private val rawResolver =
        object : NotificationSenderResolver(
            context,
            app.clearsms.sms.ContactsSource(context),
            app.clearsms.data.senderid
                .SenderIdStore(context),
        ) {
            override fun resolve(sender: String) = NotificationSender(name = sender, monogram = "X")
        }

    private val messageNotifier = MessageNotifier(context, rawResolver, iconFactory)
    private val otpNotifier = OtpNotifier(context, rawResolver, iconFactory)
    private val transactionNotifier =
        TransactionNotifier(context, Json { ignoreUnknownKeys = true }, rawResolver, iconFactory)

    private val message =
        MessageEntity(
            id = 7L,
            threadId = 3L,
            sender = "AX-HDFCBK",
            normalizedSender = "HDFCBK",
            body = "123456 is your OTP. Rs.1,299 debited from a/c **2863.",
            timestamp = 1_000L,
            category = Category.OTP,
            subCategory = SubCategory.TRANSACTION,
            extractedOtp = "123456",
            extractedDataJson = """{"amount":"1299.0","type":"debit","bank":"HDFC Bank"}""",
        )

    private fun uriOf(pendingIntent: PendingIntent): String = shadowOf(pendingIntent).savedIntent.data.toString()

    private fun requestCodeOf(pendingIntent: PendingIntent): Int = shadowOf(pendingIntent).requestCode

    @Test
    fun `plain message notification deep-links to its message`() {
        val tap = messageNotifier.build(message).contentIntent
        assertThat(uriOf(tap)).isEqualTo("clearsms://conversation/3?messageId=7")
    }

    @Test
    fun `scam warning deep-links to its message`() {
        val tap = messageNotifier.buildScam(message).contentIntent
        assertThat(uriOf(tap)).isEqualTo("clearsms://conversation/3?messageId=7")
    }

    @Test
    fun `OTP notification deep-links to its message`() {
        val tap =
            otpNotifier
                .build(message, "123456", OtpDisplaySize.DEFAULT, MessageNotifier.DEFAULT_SELECTED)
                .contentIntent
        assertThat(uriOf(tap)).isEqualTo("clearsms://conversation/3?messageId=7")
    }

    @Test
    fun `transaction notification deep-links to its message`() {
        val tap =
            transactionNotifier
                .buildNotification(message, MessageNotifier.DEFAULT_SELECTED)!!
                .contentIntent
        assertThat(uriOf(tap)).isEqualTo("clearsms://conversation/3?messageId=7")
    }

    @Test
    fun `catch-up summary carries no message deep link`() {
        // The summary is about MANY messages: it opens the app on the inbox
        // with no data uri, so no single message gets a bogus highlight.
        val router =
            IncomingMessageRouter(
                context = context,
                settingsRepository = app.clearsms.testing.FakeSettingsRepository(),
                otpNotifier = otpNotifier,
                messageNotifier = messageNotifier,
                transactionNotifier = transactionNotifier,
                applicationScope =
                    kotlinx.coroutines.CoroutineScope(
                        kotlinx.coroutines.Dispatchers.Unconfined + kotlinx.coroutines.SupervisorJob(),
                    ),
            )
        val notifier = CatchUpNotifier(context, router)
        kotlinx.coroutines.runBlocking { notifier.notifyFresh(emptyList(), CatchUpNotifier.MAX_INDIVIDUAL + 1) }
        val posted =
            shadowOf(context.getSystemService(NotificationManager::class.java))
                .getNotification(NotificationIds.CATCH_UP_SUMMARY)
        assertThat(posted).isNotNull()
        assertThat(shadowOf(posted.contentIntent).savedIntent.data).isNull()
    }

    @Test
    fun `request codes stay distinct across notification kinds for one message`() {
        messageNotifier.notifySendFailure("HDFCBK", threadId = message.threadId, messageId = message.id)
        val failure =
            shadowOf(context.getSystemService(NotificationManager::class.java))
                .getNotification(NotificationIds.SEND_FAILURE)
        val codes =
            listOf(
                requestCodeOf(messageNotifier.build(message).contentIntent),
                requestCodeOf(messageNotifier.buildScam(message).contentIntent),
                requestCodeOf(
                    otpNotifier
                        .build(message, "123456", OtpDisplaySize.DEFAULT, MessageNotifier.DEFAULT_SELECTED)
                        .contentIntent,
                ),
                requestCodeOf(
                    transactionNotifier.buildNotification(message, MessageNotifier.DEFAULT_SELECTED)!!.contentIntent,
                ),
                requestCodeOf(failure.contentIntent),
            )
        assertThat(codes).containsNoDuplicates()
    }
}
