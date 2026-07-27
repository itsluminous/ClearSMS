package app.clearsms

import android.content.ComponentName
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * IPC surface lock-down, asserted against the merged manifest: receivers that
 * back notification actions must NOT be exported (a third-party app must not
 * be able to spoof delete / mark-read / OTP-copy / reminder broadcasts), and
 * the components the default-SMS-app contract requires to be exported must
 * carry their platform-signature permission.
 */
@RunWith(RobolectricTestRunner::class)
class ManifestExportedComponentsTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val pm get() = context.packageManager

    private fun receiverExported(name: String): Boolean = pm.getReceiverInfo(ComponentName(context, name), 0).exported

    private fun receiverPermission(name: String): String? = pm.getReceiverInfo(ComponentName(context, name), 0).permission

    @Test
    fun `notification action and alarm receivers are private`() {
        for (name in listOf(
            "app.clearsms.notification.OtpActionReceiver",
            "app.clearsms.notification.MessageActionReceiver",
            "app.clearsms.receiver.ReminderAlarmReceiver",
            "app.clearsms.receiver.SmsSentReceiver",
        )) {
            assertThat(receiverExported(name)).isFalse()
        }
    }

    @Test
    fun `exported sms receivers require platform signature permissions`() {
        assertThat(receiverExported("app.clearsms.receiver.SmsReceiver")).isTrue()
        assertThat(receiverPermission("app.clearsms.receiver.SmsReceiver"))
            .isEqualTo("android.permission.BROADCAST_SMS")
        assertThat(receiverExported("app.clearsms.receiver.MmsWapPushReceiver")).isTrue()
        assertThat(receiverPermission("app.clearsms.receiver.MmsWapPushReceiver"))
            .isEqualTo("android.permission.BROADCAST_WAP_PUSH")
    }

    @Test
    fun `respond-via-message service is guarded by its signature permission`() {
        val info =
            pm.getServiceInfo(
                ComponentName(context, "app.clearsms.service.HeadlessSmsSendService"),
                0,
            )
        assertThat(info.exported).isTrue()
        assertThat(info.permission).isEqualTo("android.permission.SEND_RESPOND_VIA_MESSAGE")
    }
}
