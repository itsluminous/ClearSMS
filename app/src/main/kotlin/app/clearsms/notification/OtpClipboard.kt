package app.clearsms.notification

import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.os.PersistableBundle
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

    /** Copies [otp] to the clipboard, flagged sensitive, with a timed clear. */
    fun copy(
        context: Context,
        otp: String,
        clearScope: CoroutineScope,
    ) {
        val clipboard = context.getSystemService(ClipboardManager::class.java) ?: return
        val label = context.getString(R.string.otp_clip_label)
        val clip = ClipData.newPlainText(label, otp)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            clip.description.extras =
                PersistableBundle().apply {
                    putBoolean(ClipDescription.EXTRA_IS_SENSITIVE, true)
                }
        }
        clipboard.setPrimaryClip(clip)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            clearScope.launch {
                delay(CLEAR_AFTER_MS)
                clearIfStillOurs(clipboard, label)
            }
        }
    }

    /** Clears the primary clip only if it is still this app's OTP clip. */
    internal fun clearIfStillOurs(
        clipboard: ClipboardManager,
        label: String,
    ) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return
        if (!shouldClear(clipboard.primaryClipDescription?.label?.toString(), label)) return
        runCatching { clipboard.clearPrimaryClip() }
    }

    /** Pure decision: clear only when the current clip label is our OTP label. */
    internal fun shouldClear(
        currentLabel: String?,
        ourLabel: String,
    ): Boolean = currentLabel == ourLabel
}
