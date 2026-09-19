package app.clearsms.ui.settings

import android.annotation.SuppressLint
import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings

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

    /**
     * The system screen for this app's notifications, per SDK level:
     *
     * - API 26+: [Settings.ACTION_APP_NOTIFICATION_SETTINGS] with
     *   [Settings.EXTRA_APP_PACKAGE] - the channel list where every category
     *   (OTP, messages, promotions, transactions…) is tuned individually.
     * - API 23-25 (minSdk is 23, channels arrived in 26): the app's details
     *   page via ACTION_APPLICATION_DETAILS_SETTINGS with a `package:` URI,
     *   which carries the pre-channel notification toggle - a real
     *   destination, not a dead row.
     *
     * [sdkInt] is a parameter (defaulting to the device) so both branches
     * are testable on any JVM. The API-26 constants are inlined strings, so
     * referencing them on an older device is safe.
     */
    @SuppressLint("InlinedApi")
    fun appNotificationSettingsIntent(
        packageName: String,
        sdkInt: Int = Build.VERSION.SDK_INT,
    ): Intent =
        if (sdkInt >= Build.VERSION_CODES.O) {
            Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                .putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
        } else {
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName"))
        }

    /**
     * Opens the system notification settings for this app; returns false
     * instead of crashing when no activity resolves (some stripped-down
     * ROMs), so the caller can surface a snackbar rather than silently
     * doing nothing.
     */
    fun openAppNotificationSettings(context: Context): Boolean =
        try {
            val intent = appNotificationSettingsIntent(context.packageName)
            if (context !is Activity) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
            true
        } catch (_: ActivityNotFoundException) {
            false
        }
}
