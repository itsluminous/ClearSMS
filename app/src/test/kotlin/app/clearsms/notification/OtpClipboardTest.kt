package app.clearsms.notification

import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import androidx.test.core.app.ApplicationProvider
import app.clearsms.R
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Clipboard hygiene for OTP copies (sensitive flag + guarded timed clear).
 *
 * API-level branches are exercised by injecting the sdk level into
 * [OtpClipboard] instead of pinning `@Config(sdk = ...)` - sdk pins split
 * the Robolectric sandbox and re-trigger the native-runtime extraction
 * race (see RobolectricSandboxConventionTest).
 */
@RunWith(RobolectricTestRunner::class)
class OtpClipboardTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val clipboard get() = context.getSystemService(ClipboardManager::class.java)!!
    private val label get() = context.getString(R.string.otp_clip_label)

    @Test
    fun `app confirms its own copy below api 33`() {
        assertThat(OtpClipboard.appShouldConfirm(Build.VERSION_CODES.S)).isTrue()
    }

    @Test
    fun `app confirmation is suppressed from api 33 where the system shows its own`() {
        assertThat(OtpClipboard.appShouldConfirm(Build.VERSION_CODES.TIRAMISU)).isFalse()
        assertThat(OtpClipboard.appShouldConfirm(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)).isFalse()
    }

    @Test
    fun `copy on api 33 flags the clip sensitive`() {
        OtpClipboard.copy(
            context,
            "123456",
            CoroutineScope(Dispatchers.Unconfined),
            sdkInt = Build.VERSION_CODES.TIRAMISU,
        )
        val description = clipboard.primaryClipDescription!!
        assertThat(description.extras?.getBoolean(ClipDescription.EXTRA_IS_SENSITIVE)).isTrue()
        assertThat(
            clipboard.primaryClip!!
                .getItemAt(0)
                .text
                .toString(),
        ).isEqualTo("123456")
    }

    @Test
    fun `copy on api 28 places the otp without the sensitive flag and without crashing`() {
        OtpClipboard.copy(
            context,
            "654321",
            CoroutineScope(Dispatchers.Unconfined),
            sdkInt = Build.VERSION_CODES.P,
        )
        assertThat(
            clipboard.primaryClip!!
                .getItemAt(0)
                .text
                .toString(),
        ).isEqualTo("654321")
        val extras = clipboard.primaryClipDescription?.extras
        assertThat(extras?.getBoolean(ClipDescription.EXTRA_IS_SENSITIVE) ?: false).isFalse()
    }

    @Test
    fun `clear only fires while the clipboard still holds our otp clip`() {
        clipboard.setPrimaryClip(ClipData.newPlainText(label, "123456"))
        OtpClipboard.clearIfStillOurs(clipboard, label, sdkInt = Build.VERSION_CODES.TIRAMISU)
        val remaining =
            clipboard.primaryClip
                ?.getItemAt(0)
                ?.text
                ?.toString()
                .orEmpty()
        assertThat(remaining).isNotEqualTo("123456")
    }

    @Test
    fun `clear never wipes a clip the user copied afterwards`() {
        clipboard.setPrimaryClip(ClipData.newPlainText("user label", "user data"))
        OtpClipboard.clearIfStillOurs(clipboard, label, sdkInt = Build.VERSION_CODES.TIRAMISU)
        assertThat(
            clipboard.primaryClip!!
                .getItemAt(0)
                .text
                .toString(),
        ).isEqualTo("user data")
    }

    @Test
    fun `clear is a no-op below api 28`() {
        clipboard.setPrimaryClip(ClipData.newPlainText(label, "123456"))
        OtpClipboard.clearIfStillOurs(clipboard, label, sdkInt = Build.VERSION_CODES.O)
        assertThat(
            clipboard.primaryClip!!
                .getItemAt(0)
                .text
                .toString(),
        ).isEqualTo("123456")
    }

    @Test
    fun `shouldClear is a strict label match`() {
        assertThat(OtpClipboard.shouldClear("OTP", "OTP")).isTrue()
        assertThat(OtpClipboard.shouldClear("other", "OTP")).isFalse()
        assertThat(OtpClipboard.shouldClear(null, "OTP")).isFalse()
    }
}
