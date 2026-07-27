package app.clearsms.notification

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.net.toUri
import app.clearsms.R
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/** Bill-due reminder notification fired by the reminder alarm. */
@Singleton
class ReminderNotifier
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
    ) {
        fun notifyBillDue(
            reminderId: Long,
            bankName: String?,
            accountLast4: String?,
            totalDue: Double?,
        ) {
            Channels.ensureCreated(context)
            val title = context.getString(R.string.bill_due_title)
            val source =
                listOfNotNull(
                    bankName,
                    accountLast4?.let { context.getString(R.string.bill_due_account, it) },
                ).joinToString(" ")
            val text =
                if (totalDue != null) {
                    context.getString(R.string.bill_due_text_amount, source, totalDue)
                } else {
                    context.getString(R.string.bill_due_text, source)
                }
            val contentIntent =
                PendingIntent.getActivity(
                    context,
                    reminderId.toInt(),
                    Intent(Intent.ACTION_VIEW, "clearsms://alerts".toUri())
                        .setPackage(context.packageName)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                )
            val notification =
                NotificationCompat
                    .Builder(context, Channels.SUMMARY)
                    .setSmallIcon(R.drawable.ic_notification)
                    .setContentTitle(title)
                    .setContentText(text)
                    .setCategory(NotificationCompat.CATEGORY_REMINDER)
                    .setContentIntent(contentIntent)
                    .setAutoCancel(true)
                    .build()
            try {
                NotificationManagerCompat.from(context).notify(REMINDER_NOTIFICATION_ID_BASE + (reminderId % 1000).toInt(), notification)
            } catch (_: SecurityException) {
                // POST_NOTIFICATIONS not granted; onboarding asks for it.
            }
        }

        private companion object {
            const val REMINDER_NOTIFICATION_ID_BASE = 60_000
        }
    }
