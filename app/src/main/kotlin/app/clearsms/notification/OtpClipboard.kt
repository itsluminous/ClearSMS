package app.clearsms.notification

import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.os.PersistableBundle
import androidx.annotation.ChecksSdkIntAtLeast
import app.clearsms.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Single place OTP codes are written to the system clipboard.
 *
 * - On API 33+ every clip is flagged [ClipDescription.EXTRA_IS_SENSITIVE] so
 *   the system preview masks the code and clipboard-history keyboards can
 *   honor it.
 * - On API 28+ the clip is cleared after [CLEAR_AFTER_MS] (best effort: the
 *   timer dies with the process) - but only if the clipboard still holds this
 *   app's OTP clip, so a value the user copied in the meantime is never wiped.
 *
 * Logging convention (applies to the whole codebase): message bodies, OTP
 * codes, amounts, account numbers and phone numbers must NEVER be logged -
 * diagnostics may reference ids, counts and categories only.
 */
object OtpClipboard {
    private const val CLEAR_AFTER_MS = 60_000L

    /**
     * Copies [otp] to the clipboard, flagged sensitive, with a timed clear.
     *
     * [sdkInt] is injectable (defaulting to the device's real level) so both
     * API branches are unit-testable in the default Robolectric sandbox -
     * `@Config(sdk = ...)` pins would split the sandbox and re-trigger the
     * native-runtime extraction race (see RobolectricSandboxConventionTest).
     */
    fun copy(
        context: Context,
        otp: String,
        clearScope: CoroutineScope,
        sdkInt: Int = Build.VERSION.SDK_INT,
    ) {
        val clipboard = context.getSystemService(ClipboardManager::class.java) ?: return
        val label = context.getString(R.string.otp_clip_label)
        val clip = ClipData.newPlainText(label, otp)
        if (atLeast(sdkInt, Build.VERSION_CODES.TIRAMISU)) {
            clip.description.extras =
                PersistableBundle().apply {
                    putBoolean(ClipDescription.EXTRA_IS_SENSITIVE, true)
                }
        }
        clipboard.setPrimaryClip(clip)
        if (atLeast(sdkInt, Build.VERSION_CODES.P)) {
            clearScope.launch {
                delay(CLEAR_AFTER_MS)
                clearIfStillOurs(clipboard, label, sdkInt)
            }
        }
    }

    /** Clears the primary clip only if it is still this app's OTP clip. */
    internal fun clearIfStillOurs(
        clipboard: ClipboardManager,
        label: String,
        sdkInt: Int = Build.VERSION.SDK_INT,
    ) {
        if (!atLeast(sdkInt, Build.VERSION_CODES.P)) return
        if (!shouldClear(clipboard.primaryClipDescription?.label?.toString(), label)) return
        runCatching { clipboard.clearPrimaryClip() }
    }

    /**
     * The `sdkInt >= level` check, shaped so lint's NewApi analysis follows
     * the injectable [sdkInt] the same way it follows `Build.VERSION.SDK_INT`.
     */
    @ChecksSdkIntAtLeast(parameter = 1)
    private fun atLeast(
        sdkInt: Int,
        level: Int,
    ): Boolean = sdkInt >= level

    /** Pure decision: clear only when the current clip label is our OTP label. */
    internal fun shouldClear(
        currentLabel: String?,
        ourLabel: String,
    ): Boolean = currentLabel == ourLabel

    /**
     * Whether the app should show its OWN "OTP copied" confirmation after a
     * [copy]. From Android 13 the system overlays its own confirmation UI on
     * every `setPrimaryClip`, so an app toast/snackbar on top of it would be
     * a double confirmation - the platform guidance is to show copy feedback
     * only below 13. One rule for every copy surface (notification action,
     * in-app button) so they can never diverge.
     */
    fun appShouldConfirm(sdkInt: Int = Build.VERSION.SDK_INT): Boolean = sdkInt < Build.VERSION_CODES.TIRAMISU
}
