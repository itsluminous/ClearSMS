package app.clearsms.notification

import android.app.NotificationManager
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import app.clearsms.data.db.MessageEntity
import app.clearsms.domain.categorizer.SenderIdLookup
import app.clearsms.domain.model.Category
import app.clearsms.domain.model.SubCategory
import app.clearsms.testing.FakeSettingsRepository
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
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
 * Pinned behavior of a MUTED sender at the notification source
 * ([IncomingMessageRouter]): every notification type stays silent - plain,
 * important, promotional, spam, unknown-sender, parsed transaction /
 * balance / bill, and the OTP - while the SAME message from an unmuted
 * sender notifies (the control that keeps a silent test from hiding a
 * regression). The one deliberate survivor is the scam warning: muting is
 * a request for quiet, not for being left defenceless.
 *
 * Also pins that the mute matches the way blocking does: a TRAI route
 * prefix/suffix falls away ("VM-HDFCBK-S" is muted by "HDFCBK") and phone
 * numbers compare by their last ten digits.
 */
@RunWith(RobolectricTestRunner::class)
class MutedSenderRoutingTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val json = Json { ignoreUnknownKeys = true }
    private val iconFactory = SenderIconFactory(context)
    private val settings = FakeSettingsRepository()
    private val sectionGate = NotificationSectionGate(settings)
    private val mutedGate = MutedSenderGate(settings)

    private val rawResolver =
        object : NotificationSenderResolver(
            context,
            app.clearsms.sms.ContactsSource(context),
            SenderIdLookup { null },
        ) {
            override fun resolve(sender: String) = NotificationSender(name = sender, monogram = "X")
        }

    private val router =
        IncomingMessageRouter(
            context = context,
            settingsRepository = settings,
            otpNotifier = OtpNotifier(context, rawResolver, iconFactory, sectionGate, mutedGate),
            messageNotifier = MessageNotifier(context, rawResolver, iconFactory, sectionGate, mutedGate),
            transactionNotifier = TransactionNotifier(context, json, rawResolver, iconFactory, sectionGate, mutedGate),
            mutedSenderGate = mutedGate,
            applicationScope = CoroutineScope(Dispatchers.Unconfined + SupervisorJob()),
        )

    private fun shade() = shadowOf(context.getSystemService(NotificationManager::class.java))

    private fun message(
        id: Long,
        sender: String,
        category: Category,
        subCategory: SubCategory? = null,
        otp: String? = null,
        extracted: String? = null,
    ) = MessageEntity(
        id = id,
        threadId = id,
        sender = sender,
        normalizedSender =
            app.clearsms.data.repository.SenderNormalizer
                .normalize(sender),
        body = "synthetic body $id",
        timestamp = 1_000L + id,
        category = category,
        subCategory = subCategory,
        extractedOtp = otp,
        extractedDataJson = extracted,
    )

    private val transactionJson = """{"amount":"900.0","type":"debit","bank":"Test Bank"}"""
    private val balanceJson = """{"balance":"1500.0","bank":"Test Bank"}"""
    private val billJson = """{"total_due":"1200.0","due_date":"2026-10-01","bank":"Test Card"}"""

    /**
     * Routes [entity] once muted and once not, asserting silence then a
     * notification. Each type gets its own test so a regression names the
     * type that broke.
     */
    private fun assertMuteSilences(
        entity: MessageEntity,
        muteEntry: String = entity.normalizedSender,
    ) = runBlocking {
        settings.setMutedSenders(setOf(muteEntry))
        router.route(entity)
        assertWithMessage("${entity.category}/${entity.subCategory} must be silent while muted")
            .that(shade().size())
            .isEqualTo(0)

        settings.setMutedSenders(emptySet())
        router.route(entity)
        assertWithMessage("${entity.category}/${entity.subCategory} must notify once unmuted (control)")
            .that(shade().size())
            .isAtLeast(1)
    }

    @Test
    fun `personal message is silent while muted, notifies unmuted`() = assertMuteSilences(message(1, "9876543210", Category.PERSONAL))

    @Test
    fun `important message is silent while muted, notifies unmuted`() = assertMuteSilences(message(2, "AX-TESTCO", Category.IMPORTANT))

    @Test
    fun `promotional message is silent while muted, notifies unmuted`() = assertMuteSilences(message(3, "VM-PROMOCO", Category.PROMOTIONAL))

    @Test
    fun `spam message is silent while muted, notifies unmuted`() = assertMuteSilences(message(4, "BZ-JUNKCO", Category.SPAM))

    @Test
    fun `unknown-sender message is silent while muted, notifies unmuted`() = assertMuteSilences(message(5, "9998887776", Category.UNKNOWN))

    @Test
    fun `parsed transaction is silent while muted, notifies unmuted`() =
        assertMuteSilences(
            message(6, "AX-TESTBK", Category.IMPORTANT, SubCategory.TRANSACTION, extracted = transactionJson),
        )

    @Test
    fun `balance update is silent while muted, notifies unmuted`() =
        assertMuteSilences(
            message(7, "AX-TESTBK", Category.IMPORTANT, SubCategory.BANK_ALERT, extracted = balanceJson),
        )

    @Test
    fun `bill reminder message is silent while muted, notifies unmuted`() =
        assertMuteSilences(
            message(8, "AX-TESTCD", Category.IMPORTANT, SubCategory.BILL, extracted = billJson),
        )

    @Test
    fun `DECISION - the OTP notification is suppressed for a muted sender`() =
        assertMuteSilences(message(9, "AX-TESTBK", Category.OTP, otp = "123456"))

    @Test
    fun `a muted transaction never falls through to the plain message notification`() =
        runBlocking {
            // TransactionNotifier returns true when muted so the router's
            // transaction branch is taken and does NOT fall through to
            // MessageNotifier - which would re-notify the muted sender.
            settings.setMutedSenders(setOf("TESTBK"))
            router.route(message(10, "AX-TESTBK", Category.IMPORTANT, SubCategory.TRANSACTION, extracted = transactionJson))
            assertThat(shade().size()).isEqualTo(0)
        }

    @Test
    fun `DECISION - the scam warning is KEPT for a muted sender, and nothing else posts`() =
        runBlocking {
            settings.setMutedSenders(setOf("PHISHY"))
            val scam = message(11, "VM-PHISHY", Category.SPAM, SubCategory.SCAM)
            router.route(scam)
            assertThat(shade().size()).isEqualTo(1)
            assertThat(shade().getNotification(NotificationIds.scam(scam.id))).isNotNull()
            // No plain/spam thread notification alongside it.
            assertThat(shade().getNotification(NotificationIds.messageThread(scam.threadId))).isNull()
        }

    @Test
    fun `a scam-flagged OTP from a muted sender warns but does not post the OTP`() =
        runBlocking {
            settings.setMutedSenders(setOf("TESTBK"))
            val entity = message(12, "AX-TESTBK", Category.OTP, SubCategory.SCAM, otp = "654321")
            router.route(entity)
            assertThat(shade().getNotification(NotificationIds.scam(entity.id))).isNotNull()
            assertThat(shade().getNotification(NotificationIds.otp(entity.id))).isNull()
        }

    // region normalisation

    @Test
    fun `a TRAI route variant is muted by its bare header`() =
        assertMuteSilences(message(13, "VM-HDFCBK-S", Category.IMPORTANT), muteEntry = "HDFCBK")

    @Test
    fun `a raw prefixed entry mutes the bare header too`() =
        assertMuteSilences(message(14, "HDFCBK", Category.IMPORTANT), muteEntry = "AD-HDFCBK")

    @Test
    fun `a phone number is muted by its last ten digits in any written form`() =
        assertMuteSilences(message(15, "+91 98765 43210", Category.PERSONAL), muteEntry = "9876543210")

    @Test
    fun `a different sender sharing a prefix is not muted`() =
        runBlocking {
            settings.setMutedSenders(setOf("HDFCBK"))
            router.route(message(16, "VM-HDFCLF", Category.IMPORTANT))
            assertThat(shade().size()).isEqualTo(1)
        }

    // endregion

    // region notifier-level defence (a caller around the router)

    @Test
    fun `each per-message notifier refuses a muted sender on its own`() =
        runBlocking {
            settings.setMutedSenders(setOf("TESTBK", "9876543210"))
            val messageNotifier = MessageNotifier(context, rawResolver, iconFactory, sectionGate, mutedGate)
            val otpNotifier = OtpNotifier(context, rawResolver, iconFactory, sectionGate, mutedGate)
            val transactionNotifier = TransactionNotifier(context, json, rawResolver, iconFactory, sectionGate, mutedGate)

            messageNotifier.notify(message(20, "9876543210", Category.PERSONAL))
            otpNotifier.notify(
                message(21, "AX-TESTBK", Category.OTP, otp = "111222"),
                "111222",
                app.clearsms.domain.model.OtpDisplaySize.DEFAULT,
            )
            val handled =
                transactionNotifier.notify(
                    message(22, "AX-TESTBK", Category.IMPORTANT, SubCategory.TRANSACTION, extracted = transactionJson),
                    MessageNotifier.DEFAULT_SELECTED,
                )
            assertThat(shade().size()).isEqualTo(0)
            // "Handled" - so a router would not fall back to a plain notification.
            assertThat(handled).isTrue()

            // The scam warning is the documented exception at this level too.
            messageNotifier.notifyScam(message(23, "AX-TESTBK", Category.SPAM, SubCategory.SCAM))
            assertThat(shade().size()).isEqualTo(1)
        }

    // endregion
}
