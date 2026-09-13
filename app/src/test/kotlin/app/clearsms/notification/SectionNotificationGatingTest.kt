package app.clearsms.notification

import android.app.NotificationManager
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import app.clearsms.data.db.MessageEntity
import app.clearsms.domain.model.Category
import app.clearsms.domain.model.EnabledSections
import app.clearsms.domain.model.OtpDisplaySize
import app.clearsms.domain.model.SubCategory
import app.clearsms.testing.FakeSettingsRepository
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

/**
 * Every notification type is gated by ITS section's flag: with the section
 * on it posts, with the section off it stays out of the shade entirely -
 * the operator's requirement ("if Alerts is disabled then reminder
 * notifications should not come; if Inbox is disabled then incoming message
 * notifications should not come"), applied at the source inside each
 * notifier. NotificationSectionConventionTest pins that no notifier can
 * skip the gate.
 */
@RunWith(RobolectricTestRunner::class)
class SectionNotificationGatingTest {
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
    private val reminderNotifier = ReminderNotifier(context, gate)

    private fun message(
        id: Long,
        category: Category = Category.PERSONAL,
        subCategory: SubCategory? = null,
        otp: String? = null,
        dataJson: String? = null,
    ) = MessageEntity(
        id = id,
        threadId = id,
        sender = "SENDER",
        normalizedSender = "SENDER",
        body = "test body",
        timestamp = 1_000L,
        category = category,
        subCategory = subCategory,
        extractedOtp = otp,
        extractedDataJson = dataJson,
    )

    private fun postedCount(): Int = shadowOf(context.getSystemService(NotificationManager::class.java)).size()

    private fun sections(
        inbox: Boolean = true,
        finance: Boolean = true,
        alerts: Boolean = true,
    ) {
        settings.enabledSections.value = EnabledSections(inbox = inbox, finance = finance, alerts = alerts)
    }

    // --- Inbox ------------------------------------------------------------

    @Test
    fun `plain message notification follows the Inbox flag`() =
        runTest {
            sections(inbox = false)
            messageNotifier.notify(message(1))
            assertThat(postedCount()).isEqualTo(0)
            sections(inbox = true)
            messageNotifier.notify(message(1))
            assertThat(postedCount()).isAtLeast(1)
        }

    @Test
    fun `scam warning follows the Inbox flag`() =
        runTest {
            sections(inbox = false)
            messageNotifier.notifyScam(message(2, subCategory = SubCategory.SCAM))
            assertThat(postedCount()).isEqualTo(0)
            sections(inbox = true)
            messageNotifier.notifyScam(message(2, subCategory = SubCategory.SCAM))
            assertThat(postedCount()).isAtLeast(1)
        }

    @Test
    fun `OTP notification follows the Inbox flag - an OTP is a message`() =
        runTest {
            val otpMessage = message(3, category = Category.OTP, otp = "123456")
            sections(inbox = false)
            otpNotifier.notify(otpMessage, "123456", OtpDisplaySize.DEFAULT)
            assertThat(postedCount()).isEqualTo(0)
            sections(inbox = true)
            otpNotifier.notify(otpMessage, "123456", OtpDisplaySize.DEFAULT)
            assertThat(postedCount()).isAtLeast(1)
        }

    @Test
    fun `catch-up summary follows the Inbox flag - it announces new messages`() =
        runTest {
            val router =
                IncomingMessageRouter(
                    context,
                    settings,
                    otpNotifier,
                    messageNotifier,
                    transactionNotifier,
                    this,
                )
            val catchUp = CatchUpNotifier(context, router, gate)
            sections(inbox = false)
            catchUp.notifyFresh(emptyList(), CatchUpNotifier.MAX_INDIVIDUAL + 1)
            assertThat(postedCount()).isEqualTo(0)
            sections(inbox = true)
            catchUp.notifyFresh(emptyList(), CatchUpNotifier.MAX_INDIVIDUAL + 1)
            assertThat(postedCount()).isAtLeast(1)
        }

    // --- Finance ----------------------------------------------------------

    @Test
    fun `parsed transaction notification follows the Finance flag`() =
        runTest {
            val tx =
                message(
                    4,
                    category = Category.IMPORTANT,
                    subCategory = SubCategory.TRANSACTION,
                    dataJson = """{"amount":"1299.0","type":"debit","bank":"Test Bank"}""",
                )
            sections(finance = false)
            // Returns false like the transaction toggle being off, so the
            // router falls through to the (Inbox-gated) plain notification.
            assertThat(transactionNotifier.notify(tx, MessageNotifier.DEFAULT_SELECTED)).isFalse()
            assertThat(postedCount()).isEqualTo(0)
            sections(finance = true)
            assertThat(transactionNotifier.notify(tx, MessageNotifier.DEFAULT_SELECTED)).isTrue()
            assertThat(postedCount()).isAtLeast(1)
        }

    // --- Alerts -----------------------------------------------------------

    @Test
    fun `bill-due reminder follows the Alerts flag - a stale alarm stays silent`() =
        runTest {
            sections(alerts = false)
            reminderNotifier.notifyBillDue(reminderId = 9L, bankName = "Test Bank", accountLast4 = null, totalDue = 100.0)
            assertThat(postedCount()).isEqualTo(0)
            sections(alerts = true)
            reminderNotifier.notifyBillDue(reminderId = 9L, bankName = "Test Bank", accountLast4 = null, totalDue = 100.0)
            assertThat(postedCount()).isAtLeast(1)
        }
}
