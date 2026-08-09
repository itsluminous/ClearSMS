package app.clearsms.notification

import android.app.NotificationManager
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import app.clearsms.data.db.MessageEntity
import app.clearsms.domain.model.Category
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
 * Fresh messages surfaced by a catch-up import must notify exactly like live
 * deliveries (same router, channels and ids - so cancellation keeps working),
 * collapse to a single summary past [CatchUpNotifier.MAX_INDIVIDUAL], and
 * stay silent when nothing is fresh (the initial onboarding import).
 */
@RunWith(RobolectricTestRunner::class)
class CatchUpNotifierTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val json = Json { ignoreUnknownKeys = true }
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

    private val router =
        IncomingMessageRouter(
            context = context,
            settingsRepository = FakeSettingsRepository(),
            otpNotifier = OtpNotifier(context, rawResolver, iconFactory),
            messageNotifier = MessageNotifier(context, rawResolver, iconFactory),
            transactionNotifier = TransactionNotifier(context, json, rawResolver, iconFactory),
            applicationScope = CoroutineScope(Dispatchers.Unconfined + SupervisorJob()),
        )
    private val notifier = CatchUpNotifier(context, router)

    private fun personal(
        id: Long,
        threadId: Long = id,
    ) = MessageEntity(
        id = id,
        threadId = threadId,
        sender = "987654321$id",
        normalizedSender = "987654321$id",
        body = "hey there $id",
        timestamp = 1_000L + id,
        category = Category.PERSONAL,
    )

    private val otpMessage =
        MessageEntity(
            id = 21L,
            threadId = 5L,
            sender = "AX-HDFCBK",
            normalizedSender = "HDFCBK",
            body = "123456 is your OTP",
            timestamp = 2_000L,
            category = Category.OTP,
            extractedOtp = "123456",
        )

    private val transactionMessage =
        MessageEntity(
            id = 22L,
            threadId = 6L,
            sender = "AX-ICICIB",
            normalizedSender = "ICICIB",
            body = "Rs.900 debited from a/c **1111",
            timestamp = 3_000L,
            category = Category.IMPORTANT,
            subCategory = SubCategory.TRANSACTION,
            extractedDataJson = """{"amount":"900.0","type":"debit","bank":"ICICI Bank"}""",
        )

    private fun shade() = shadowOf(context.getSystemService(NotificationManager::class.java))

    @Test
    fun `nothing fresh posts nothing`() =
        runBlocking {
            notifier.notifyFresh(emptyList(), 0)
            assertThat(shade().size()).isEqualTo(0)
        }

    @Test
    fun `few fresh messages notify individually through the live pipeline`() =
        runBlocking {
            notifier.notifyFresh(listOf(personal(1), personal(2)), 2)
            assertThat(shade().size()).isEqualTo(2)
            assertThat(shade().getNotification(NotificationIds.messageThread(1L))).isNotNull()
            assertThat(shade().getNotification(NotificationIds.messageThread(2L))).isNotNull()
            assertThat(shade().getNotification(NotificationIds.CATCH_UP_SUMMARY)).isNull()
        }

    @Test
    fun `fresh OTP routes to the OTP notifier`() =
        runBlocking {
            notifier.notifyFresh(listOf(otpMessage), 1)
            assertThat(shade().getNotification(NotificationIds.otp(otpMessage.id))).isNotNull()
        }

    @Test
    fun `fresh transaction routes to the transaction notifier`() =
        runBlocking {
            notifier.notifyFresh(listOf(transactionMessage), 1)
            assertThat(shade().getNotification(NotificationIds.transaction(transactionMessage.id))).isNotNull()
        }

    @Test
    fun `blocked sender stays silent even when fresh`() =
        runBlocking {
            notifier.notifyFresh(listOf(personal(3).copy(isBlockedSender = true)), 1)
            assertThat(shade().size()).isEqualTo(0)
        }

    @Test
    fun `more than the cap collapses to a single summary`() =
        runBlocking {
            // The importer only samples up to the cap; the count carries the truth.
            notifier.notifyFresh((1L..5L).map(::personal), 12)
            assertThat(shade().size()).isEqualTo(1)
            assertThat(shade().getNotification(NotificationIds.CATCH_UP_SUMMARY)).isNotNull()
        }

    @Test
    fun `reading a thread cancels the catch-up summary`() =
        runBlocking {
            notifier.notifyFresh((1L..5L).map(::personal), 12)
            assertThat(shade().getNotification(NotificationIds.CATCH_UP_SUMMARY)).isNotNull()
            NotificationDismisser(context).cancelThreads(listOf(1L))
            assertThat(shade().getNotification(NotificationIds.CATCH_UP_SUMMARY)).isNull()
        }

    @Test
    fun `reading a catch-up-notified thread cancels its notification`() =
        runBlocking {
            notifier.notifyFresh(listOf(personal(4)), 1)
            assertThat(shade().getNotification(NotificationIds.messageThread(4L))).isNotNull()
            NotificationDismisser(context).cancelThreads(listOf(4L))
            assertThat(shade().getNotification(NotificationIds.messageThread(4L))).isNull()
        }
}
