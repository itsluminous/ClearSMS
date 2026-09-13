package app.clearsms.work

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import app.clearsms.data.db.ReminderDao
import app.clearsms.data.db.ReminderEntity
import app.clearsms.data.prefs.SettingsRepository
import app.clearsms.receiver.ReminderAlarmReceiver
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.first
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Schedules bill-due alarms via [AlarmManager]: one alarm per reminder, one
 * day before the due date.
 *
 * Exact alarms are used when permitted; on Android S+ where the user has not
 * granted `SCHEDULE_EXACT_ALARM`, the scheduler falls back to an inexact
 * while-idle alarm (a reminder a few minutes late is still useful).
 */
@Singleton
class ReminderAlarmScheduler
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
        private val reminderDao: ReminderDao,
        private val settingsRepository: SettingsRepository,
    ) {
        /**
         * Alerts-section gate, consulted before ANY alarm registration: the
         * requirement is that a disabled Alerts section stops the WAKE-UPS,
         * not merely the notification at fire time. Both public entry points
         * ([scheduleForMessage], [rescheduleAll]) check it, so neither a new
         * bill message nor a reboot re-registers alarms while Alerts is off.
         * [app.clearsms.ui.settings.SettingsViewModel] pairs the toggle with
         * [cancelUpcoming] / [rescheduleAll]; the notifier's own gate covers
         * any alarm that still slips through the race.
         */
        private suspend fun alertsEnabled(): Boolean = settingsRepository.enabledSections.first().alerts

        /** Schedules the alarm for the reminder extracted from [messageId], if any. */
        suspend fun scheduleForMessage(messageId: Long) {
            if (!alertsEnabled()) return
            reminderDao.findByRawSmsId(messageId)?.let { schedule(it) }
        }

        /** Re-registers alarms for every upcoming reminder (after boot). */
        suspend fun rescheduleAll() {
            if (!alertsEnabled()) return
            val now = System.currentTimeMillis()
            reminderDao.observeUpcoming(now).first().forEach { schedule(it) }
        }

        /**
         * Cancels every upcoming reminder's alarm - the disable-time half of
         * the Alerts gate (alarms registered while the section was on would
         * otherwise still wake the device).
         */
        suspend fun cancelUpcoming() {
            val alarmManager = context.getSystemService(AlarmManager::class.java) ?: return
            val now = System.currentTimeMillis()
            reminderDao.observeUpcoming(now).first().forEach { reminder ->
                alarmManager.cancel(alarmPendingIntent(reminder))
            }
        }

        /** Schedules a single alarm one day before [reminder]'s due date. */
        private fun schedule(reminder: ReminderEntity) {
            val dueDate = reminder.dueDate ?: return
            val triggerAt = triggerTimeFor(dueDate, System.currentTimeMillis()) ?: return
            val alarmManager = context.getSystemService(AlarmManager::class.java) ?: return
            val operation = alarmPendingIntent(reminder)
            if (canScheduleExact(alarmManager)) {
                alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, operation)
            } else {
                alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, operation)
            }
        }

        private fun canScheduleExact(alarmManager: AlarmManager): Boolean =
            Build.VERSION.SDK_INT < Build.VERSION_CODES.S || alarmManager.canScheduleExactAlarms()

        private fun alarmPendingIntent(reminder: ReminderEntity): PendingIntent {
            val intent =
                Intent(context, ReminderAlarmReceiver::class.java)
                    .setAction(ReminderAlarmReceiver.ACTION_BILL_DUE)
                    .putExtra(ReminderAlarmReceiver.EXTRA_REMINDER_ID, reminder.id)
                    .putExtra(ReminderAlarmReceiver.EXTRA_BANK_NAME, reminder.bankName)
                    .putExtra(ReminderAlarmReceiver.EXTRA_ACCOUNT_LAST4, reminder.accountLast4)
                    .putExtra(ReminderAlarmReceiver.EXTRA_TOTAL_DUE, reminder.totalDue ?: Double.NaN)
            return PendingIntent.getBroadcast(
                context,
                reminder.id.toInt(),
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        }

        companion object {
            /**
             * The alarm fires one day before the due date (at the same
             * start-of-day instant). Returns null when that moment has
             * already passed - there is nothing useful to schedule.
             */
            fun triggerTimeFor(
                dueDateMs: Long,
                nowMs: Long,
            ): Long? {
                val triggerAt = dueDateMs - TimeUnit.DAYS.toMillis(1)
                return triggerAt.takeIf { it > nowMs }
            }
        }
    }
