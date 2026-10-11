package app.clearsms.notification

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import app.clearsms.data.prefs.SettingsRepository
import app.clearsms.ui.settings.DirectCall
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Invisible target of the notification's Call action (GitHub #99): decides
 * AT TAP TIME whether the tap opens the dialer prefilled or places the
 * call, then finishes. It exists so that decision can be made live rather
 * than when the notification was built:
 *
 * - the "Direct call" setting is read NOW, so a toggle flipped after the
 *   notification was posted is honoured;
 * - the CALL_PHONE grant is checked NOW ([DirectCall.place]), so a
 *   permission revoked since posting ends in the dialer instead of a
 *   PendingIntent the platform refuses with nothing on screen - the
 *   "silently does nothing" outcome a direct call must never have.
 *
 * It is an ACTIVITY, not a receiver, because Android 12+ forbids starting an
 * activity from a receiver or service a notification tap triggered
 * (notification trampolines); activity-to-activity is allowed. Not
 * exported: only this app's own PendingIntents reach it. Translucent, with
 * no history and excluded from recents, so the user only ever sees the
 * dialer or the call.
 *
 * The number is re-gated through [NotificationActionPlanner.dialableAddress]
 * (DialableNumber) - the same rule that decided the button existed - so a
 * crafted or stale extra can never dial something that rule refuses.
 */
@AndroidEntryPoint
class CallActionActivity : ComponentActivity() {
    @Inject
    lateinit var settings: SettingsRepository

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val number = intent?.getStringExtra(EXTRA_NUMBER)?.let(NotificationActionPlanner::dialableAddress)
        if (number == null) {
            finish()
            return
        }
        lifecycleScope.launch {
            DirectCall.place(this@CallActionActivity, number, settings.directCall.first())
            finish()
        }
    }

    companion object {
        const val EXTRA_NUMBER = "app.clearsms.extra.CALL_NUMBER"

        /** The explicit intent MessageActionFactory wraps in the Call action's PendingIntent. */
        fun intent(
            context: Context,
            number: String,
        ): Intent =
            Intent(context, CallActionActivity::class.java)
                .putExtra(EXTRA_NUMBER, number)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
}
