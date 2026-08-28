package app.clearsms.ui.settings

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri

/**
 * Hands a URL to whatever app claims it (browser for https, a UPI app for
 * upi://). Uses a plain ACTION_VIEW intent - the app has no INTERNET
 * permission and needs none to delegate a link to another app.
 */
object ExternalLinks {
    /**
     * The exact intent [open] fires, exposed so tests can assert on it.
     *
     * A `tel:` URI uses ACTION_DIAL, not ACTION_VIEW: dial opens the phone
     * app with the number filled in and waits for the user to press call,
     * needs no CALL_PHONE permission, and cannot place a call by itself - the
     * right contract for a number that arrived in an SMS.
     */
    fun intent(url: String): Intent {
        val uri = Uri.parse(url)
        val action = if (uri.scheme.equals("tel", ignoreCase = true)) Intent.ACTION_DIAL else Intent.ACTION_VIEW
        return Intent(action, uri)
    }

    /**
     * Launches the link; returns false instead of crashing when no app can
     * handle it (no browser, or no UPI app for a upi:// link) so callers
     * can surface a snackbar.
     */
    fun open(
        context: Context,
        url: String,
    ): Boolean =
        try {
            val intent = intent(url)
            // Launching from a non-Activity context needs its own task.
            if (context !is Activity) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
            true
        } catch (_: ActivityNotFoundException) {
            false
        }
}
