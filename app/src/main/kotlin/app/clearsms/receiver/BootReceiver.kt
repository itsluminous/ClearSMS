package app.clearsms.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import app.clearsms.di.ApplicationScope
import app.clearsms.work.MessageScheduler
import app.clearsms.work.ReminderAlarmScheduler
import app.clearsms.work.WorkScheduler
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Restores background work after a reboot: periodic WorkManager jobs are
 * re-enqueued and every pending alarm - bill-due reminders and scheduled
 * sends - is re-registered (AlarmManager alarms do not survive a reboot).
 * A scheduled send whose time passed while the device was off fires
 * immediately.
 *
 * TIME_SET / TIMEZONE_CHANGED re-arm scheduled sends too: their alarms were
 * registered against the old wall clock, and "send at 9:00" means 9:00 on
 * the clock the user sees now. All three actions are protected broadcasts
 * only the system can send.
 */
@AndroidEntryPoint
class BootReceiver : BroadcastReceiver() {
    @Inject
    lateinit var reminderAlarmScheduler: ReminderAlarmScheduler

    @Inject
    lateinit var messageScheduler: MessageScheduler

    @Inject
    @ApplicationScope
    lateinit var applicationScope: CoroutineScope

    override fun onReceive(
        context: Context,
        intent: Intent,
    ) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED -> {
                WorkScheduler.scheduleAll(context)
                val pendingResult = goAsync()
                applicationScope.launch {
                    try {
                        reminderAlarmScheduler.rescheduleAll()
                        messageScheduler.rearmAll()
                    } finally {
                        pendingResult.finish()
                    }
                }
            }
            Intent.ACTION_TIME_CHANGED, Intent.ACTION_TIMEZONE_CHANGED -> {
                val pendingResult = goAsync()
                applicationScope.launch {
                    try {
                        messageScheduler.rearmAll()
                    } finally {
                        pendingResult.finish()
                    }
                }
            }
        }
    }
}
