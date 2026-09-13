package app.clearsms.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import app.clearsms.di.ApplicationScope
import app.clearsms.notification.ReminderNotifier
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Fires one day before a bill's due date (scheduled by
 * [app.clearsms.work.ReminderAlarmScheduler]) and shows the bill-due
 * notification.
 */
@AndroidEntryPoint
class ReminderAlarmReceiver : BroadcastReceiver() {
    @Inject
    lateinit var reminderNotifier: ReminderNotifier

    @Inject
    @ApplicationScope
    lateinit var applicationScope: CoroutineScope

    override fun onReceive(
        context: Context,
        intent: Intent,
    ) {
        if (intent.action != ACTION_BILL_DUE) return
        val totalDue = intent.getDoubleExtra(EXTRA_TOTAL_DUE, Double.NaN)
        // The notifier is suspend (it consults the Alerts section gate, so a
        // stale alarm registered before the section was disabled stays
        // silent); bridge the broadcast like SmsReceiver does.
        val pendingResult = goAsync()
        applicationScope.launch {
            try {
                reminderNotifier.notifyBillDue(
                    reminderId = intent.getLongExtra(EXTRA_REMINDER_ID, 0L),
                    bankName = intent.getStringExtra(EXTRA_BANK_NAME),
                    accountLast4 = intent.getStringExtra(EXTRA_ACCOUNT_LAST4),
                    totalDue = totalDue.takeUnless { it.isNaN() },
                )
            } finally {
                pendingResult.finish()
            }
        }
    }

    companion object {
        const val ACTION_BILL_DUE = "app.clearsms.action.BILL_DUE"
        const val EXTRA_REMINDER_ID = "reminder_id"
        const val EXTRA_BANK_NAME = "bank_name"
        const val EXTRA_ACCOUNT_LAST4 = "account_last4"
        const val EXTRA_TOTAL_DUE = "total_due"
    }
}
