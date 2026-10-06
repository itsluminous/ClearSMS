package app.clearsms.notification

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import app.clearsms.data.db.MessageEntity
import app.clearsms.domain.categorizer.SenderIdLookup
import app.clearsms.domain.model.Category
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

/**
 * Pinned behavior of the OTP auto-copy setting at the notification source
 * ([IncomingMessageRouter]).
 *
 * The regression this guards (issue #95): auto-copy used to be gated on
 * `SDK_INT < Q`, on the belief that Android 10's "limited access to
 * clipboard data" restriction covered writes. It does not - AOSP's
 * ClipboardService allows `OP_WRITE_CLIPBOARD` "without focus" and
 * restricts only `OP_READ_CLIPBOARD` - so the setting, which is ON by
 * default, silently did nothing on effectively every device in use.
 *
 * These tests run at the suite's default API level (well above Q), so a
 * re-introduced version gate fails them immediately. No `@Config(sdk=...)`
 * pin: sdk pins split the Robolectric sandbox (see
 * RobolectricSandboxConventionTest).
 */
@RunWith(RobolectricTestRunner::class)
class OtpAutoCopyRoutingTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val json = Json { ignoreUnknownKeys = true }
    private val iconFactory = SenderIconFactory(context)
    private val settings = FakeSettingsRepository()
    private val sectionGate = NotificationSectionGate(settings)
    private val mutedGate = MutedSenderGate(settings)
    private val clipboard get() = context.getSystemService(ClipboardManager::class.java)!!

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

    private fun otpMessage(otp: String) =
        MessageEntity(
            id = 1L,
            threadId = 1L,
            sender = "VM-HDFCBK",
            normalizedSender =
                app.clearsms.data.repository.SenderNormalizer
                    .normalize("VM-HDFCBK"),
            body = "Your OTP is $otp",
            timestamp = 1_000L,
            category = Category.OTP,
            extractedOtp = otp,
        )

    private fun clipText(): String? =
        clipboard.primaryClip
            ?.takeIf { it.itemCount > 0 }
            ?.getItemAt(0)
            ?.text
            ?.toString()

    @Test
    fun `an incoming otp lands on the clipboard when auto copy is on`() {
        settings.otpAutoCopy.value = true
        runBlocking { router.route(otpMessage("884412")) }
        assertThat(clipText()).isEqualTo("884412")
    }

    @Test
    fun `auto copy off leaves the clipboard alone`() {
        settings.otpAutoCopy.value = false
        clipboard.setPrimaryClip(ClipData.newPlainText("user label", "something the user copied"))
        runBlocking { router.route(otpMessage("112233")) }
        assertThat(clipText()).isEqualTo("something the user copied")
    }
}
