package app.clearsms.notification

import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import app.clearsms.R
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Clipboard hygiene for OTP copies (sensitive flag + guarded timed clear). */
@RunWith(RobolectricTestRunner::class)
class OtpClipboardTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val clipboard get() = context.getSystemService(ClipboardManager::class.java)!!
    private val label get() = context.getString(R.string.otp_clip_label)

    @Config(sdk = [33])
    @Test
    fun `copy on api 33 flags the clip sensitive`() {
        OtpClipboard.copy(context, "123456", CoroutineScope(Dispatchers.Unconfined))
        val description = clipboard.primaryClipDescription!!
        assertThat(description.extras?.getBoolean(ClipDescription.EXTRA_IS_SENSITIVE)).isTrue()
        assertThat(
            clipboard.primaryClip!!
                .getItemAt(0)
                .text
                .toString(),
        ).isEqualTo("123456")
    }

    @Config(sdk = [28])
    @Test
    fun `copy on api 28 places the otp without crashing`() {
        OtpClipboard.copy(context, "654321", CoroutineScope(Dispatchers.Unconfined))
        assertThat(
            clipboard.primaryClip!!
                .getItemAt(0)
                .text
                .toString(),
        ).isEqualTo("654321")
    }

    @Config(sdk = [33])
    @Test
    fun `clear only fires while the clipboard still holds our otp clip`() {
        clipboard.setPrimaryClip(ClipData.newPlainText(label, "123456"))
        OtpClipboard.clearIfStillOurs(clipboard, label)
        val remaining =
            clipboard.primaryClip
                ?.getItemAt(0)
                ?.text
                ?.toString()
                .orEmpty()
        assertThat(remaining).isNotEqualTo("123456")
    }

    @Config(sdk = [33])
    @Test
    fun `clear never wipes a clip the user copied afterwards`() {
        clipboard.setPrimaryClip(ClipData.newPlainText("user label", "user data"))
        OtpClipboard.clearIfStillOurs(clipboard, label)
        assertThat(
            clipboard.primaryClip!!
                .getItemAt(0)
                .text
                .toString(),
        ).isEqualTo("user data")
    }

    @Test
    fun `shouldClear is a strict label match`() {
        assertThat(OtpClipboard.shouldClear("OTP", "OTP")).isTrue()
        assertThat(OtpClipboard.shouldClear("other", "OTP")).isFalse()
        assertThat(OtpClipboard.shouldClear(null, "OTP")).isFalse()
    }
}
