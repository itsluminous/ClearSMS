package app.clearsms.ui.settings

import android.Manifest
import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import androidx.core.content.ContextCompat

/**
 * The ONE place a Call button becomes an intent - for the conversation
 * title bar's call button AND the notification's Call action
 * (CallActionActivity), so the two can never disagree about what a tap
 * does (GitHub #99 established that they share one dialability predicate,
 * DialableNumber, and one `tel:` mapping; this extends that contract to
 * the opt-in direct call rather than breaking it).
 *
 * Two behaviours, chosen by the user's "Direct call" setting
 * ([app.clearsms.data.prefs.SettingsRepository.directCall], default OFF):
 *
 * - OFF (the default, and the maintainer's stance in #99): ACTION_DIAL
 *   through [ExternalLinks]' `tel:` mapping - the dialer opens PREFILLED
 *   and waits for the user to press its own call button. Needs no
 *   permission, cannot place a call by itself.
 * - ON: ACTION_CALL, which places the call IMMEDIATELY with no further
 *   confirmation. This needs the CALL_PHONE runtime permission, so it is
 *   used only while [isPermitted] - a tap with the permission revoked
 *   falls back to the dialer rather than doing nothing (a direct call that
 *   silently fails is the worst outcome; a dialer that opens is never
 *   wrong).
 *
 * Eligibility is NOT decided here: whether a sender gets a Call button at
 * all is still DialableNumber's call, on both surfaces. Direct call changes
 * only what happens on tap, never who gets the button.
 */
object DirectCall {
    /** The one dangerous permission ACTION_CALL needs. Requested only when the toggle is switched on. */
    const val PERMISSION: String = Manifest.permission.CALL_PHONE

    /** Whether THIS install currently holds [PERMISSION] - re-checked at every call, never cached. */
    fun isPermitted(context: Context): Boolean =
        ContextCompat.checkSelfPermission(context, PERMISSION) == PackageManager.PERMISSION_GRANTED

    /**
     * The intent a Call button fires, as a pure mapping so tests can pin it:
     * ACTION_CALL only when the user opted in AND the permission is granted;
     * in every other case the same ACTION_DIAL `tel:` intent as before.
     */
    fun intent(
        number: String,
        directCall: Boolean,
        permitted: Boolean,
    ): Intent =
        if (directCall && permitted) {
            Intent(Intent.ACTION_CALL, Uri.parse("tel:$number"))
        } else {
            ExternalLinks.intent("tel:$number")
        }

    /**
     * Fires the Call button for [number]. Returns false only when no app can
     * take the number at all (no dialer), so the caller can show a snackbar.
     *
     * The permission is read HERE, at call time, not when the button was
     * built: a grant revoked in system settings after the notification was
     * posted still ends in the dialer. If the platform nevertheless refuses
     * the call (the grant vanished between the check and the launch), the
     * SecurityException is caught and the dialer opens instead.
     */
    fun place(
        context: Context,
        number: String,
        directCall: Boolean,
    ): Boolean {
        val intent = intent(number, directCall, isPermitted(context))
        // Launching from a non-Activity context needs its own task.
        if (context !is Activity) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return try {
            context.startActivity(intent)
            true
        } catch (_: SecurityException) {
            ExternalLinks.open(context, "tel:$number")
        } catch (_: ActivityNotFoundException) {
            false
        }
    }
}
