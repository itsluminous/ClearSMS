package app.clearsms.work

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import app.clearsms.receiver.ScheduledSendReceiver
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * AlarmManager plumbing for scheduled sends: one alarm per scheduled
 * message, keyed by its row id.
 *
 * Exact while-idle alarms are used when permitted. On Android S+ where the
 * user has denied `SCHEDULE_EXACT_ALARM` (checked via
 * [AlarmManager.canScheduleExactAlarms]) the alarm falls back to an inexact
 * while-idle one - a scheduled message a few minutes late still beats a
 * message that never sends; the settings entry point is surfaced
 * contextually by the picker UI, never demanded at startup.
 */
@Singleton
class ScheduledSendAlarms
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
    ) {
        /**
         * Registers (or re-registers, same PendingIntent identity) the alarm
         * for [messageId] at [triggerAtMs].
         *
         * @return true when an EXACT alarm was set, false for the inexact
         *   fallback (exact-alarm permission denied on S+).
         */
        fun arm(
            messageId: Long,
            triggerAtMs: Long,
        ): Boolean {
            val alarmManager = context.getSystemService(AlarmManager::class.java) ?: return false
            val exact = canScheduleExact(alarmManager)
            val operation = pendingIntent(messageId)
            if (exact) {
                alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAtMs, operation)
            } else {
                alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAtMs, operation)
            }
            return exact
        }

        /** Cancels the alarm for [messageId] (no-op when none is registered). */
        fun cancel(messageId: Long) {
            val alarmManager = context.getSystemService(AlarmManager::class.java) ?: return
            alarmManager.cancel(pendingIntent(messageId))
        }

        /** Whether the next [arm] will be exact (drives the picker's hint). */
        fun exactAlarmsAllowed(): Boolean {
            val alarmManager = context.getSystemService(AlarmManager::class.java) ?: return false
            return canScheduleExact(alarmManager)
        }

        private fun canScheduleExact(alarmManager: AlarmManager): Boolean =
            Build.VERSION.SDK_INT < Build.VERSION_CODES.S || alarmManager.canScheduleExactAlarms()

        private fun pendingIntent(messageId: Long): PendingIntent {
            val intent =
                Intent(context, ScheduledSendReceiver::class.java)
                    .setAction(ScheduledSendReceiver.ACTION_SEND_SCHEDULED)
                    .putExtra(ScheduledSendReceiver.EXTRA_MESSAGE_ID, messageId)
            return PendingIntent.getBroadcast(
                context,
                messageId.toInt(),
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        }
    }
