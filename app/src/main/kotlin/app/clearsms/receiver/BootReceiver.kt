package app.clearsms.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import app.clearsms.di.ApplicationScope
import app.clearsms.work.ReminderAlarmScheduler
import app.clearsms.work.WorkScheduler
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Restores background work after a reboot: periodic WorkManager jobs are
 * re-enqueued and every pending bill-due alarm is re-registered (AlarmManager
 * alarms do not survive a reboot).
 */
@AndroidEntryPoint
class BootReceiver : BroadcastReceiver() {
    @Inject
    lateinit var reminderAlarmScheduler: ReminderAlarmScheduler

    @Inject
    @ApplicationScope
    lateinit var applicationScope: CoroutineScope

    override fun onReceive(
        context: Context,
        intent: Intent,
    ) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        WorkScheduler.scheduleAll(context)
        val pendingResult = goAsync()
        applicationScope.launch {
            try {
                reminderAlarmScheduler.rescheduleAll()
            } finally {
                pendingResult.finish()
            }
        }
    }
}
