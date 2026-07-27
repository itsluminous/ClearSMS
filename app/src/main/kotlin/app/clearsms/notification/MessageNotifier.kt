package app.clearsms.notification

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.Person
import androidx.core.net.toUri
import app.clearsms.R
import app.clearsms.data.db.MessageEntity
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Notifications for regular incoming messages and security warnings.
 *
 * One [NotificationCompat.MessagingStyle] notification per thread; tapping it
 * deep-links into the conversation via a `clearsms://conversation/<threadId>`
 * uri handled by the main activity's navigation graph.
 */
@Singleton
class MessageNotifier
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
    ) {
        /** Posts / updates the notification for [message]'s thread. */
        fun notify(message: MessageEntity) {
            Channels.ensureCreated(context)
            val sender =
                Person
                    .Builder()
                    .setName(message.sender)
                    .setKey(message.normalizedSender)
                    .build()
            val style =
                NotificationCompat
                    .MessagingStyle(Person.Builder().setName(context.getString(R.string.notification_me)).build())
                    .addMessage(message.body, message.timestamp, sender)
            val notification =
                NotificationCompat
                    .Builder(context, Channels.MESSAGES)
                    .setSmallIcon(R.drawable.ic_notification)
                    .setContentTitle(message.sender)
                    .setContentText(message.body)
                    .setStyle(style)
                    .setCategory(NotificationCompat.CATEGORY_MESSAGE)
                    .setContentIntent(conversationIntent(message.threadId))
                    .setAutoCancel(true)
                    .build()
            post(threadNotificationId(message.threadId), notification)
        }

        /** High-priority warning for a message flagged as a likely scam. */
        fun notifyScam(message: MessageEntity) {
            Channels.ensureCreated(context)
            val notification =
                NotificationCompat
                    .Builder(context, Channels.SECURITY)
                    .setSmallIcon(R.drawable.ic_notification)
                    .setContentTitle(context.getString(R.string.scam_warning_title))
                    .setContentText(context.getString(R.string.scam_warning_text, message.sender))
                    .setStyle(NotificationCompat.BigTextStyle().bigText(message.body))
                    .setPriority(NotificationCompat.PRIORITY_HIGH)
                    .setCategory(NotificationCompat.CATEGORY_MESSAGE)
                    .setContentIntent(conversationIntent(message.threadId))
                    .setAutoCancel(true)
                    .build()
            post(SCAM_NOTIFICATION_ID_BASE + (message.id % 1000).toInt(), notification)
        }

        /** Shown when an outgoing message could not be sent. */
        fun notifySendFailure(destination: String) {
            Channels.ensureCreated(context)
            val notification =
                NotificationCompat
                    .Builder(context, Channels.MESSAGES)
                    .setSmallIcon(R.drawable.ic_notification)
                    .setContentTitle(context.getString(R.string.send_failed_title))
                    .setContentText(context.getString(R.string.send_failed_text, destination))
                    .setCategory(NotificationCompat.CATEGORY_ERROR)
                    .setAutoCancel(true)
                    .build()
            post(SEND_FAILURE_NOTIFICATION_ID, notification)
        }

        fun cancelThread(threadId: Long) {
            NotificationManagerCompat.from(context).cancel(threadNotificationId(threadId))
        }

        private fun conversationIntent(threadId: Long): PendingIntent {
            // Explicit class name avoids a compile-time dependency on the UI layer.
            val intent =
                Intent(Intent.ACTION_VIEW, "clearsms://conversation/$threadId".toUri())
                    .setPackage(context.packageName)
                    .putExtra(EXTRA_THREAD_ID, threadId)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            return PendingIntent.getActivity(
                context,
                threadId.toInt(),
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        }

        private fun post(
            id: Int,
            notification: android.app.Notification,
        ) {
            try {
                NotificationManagerCompat.from(context).notify(id, notification)
            } catch (_: SecurityException) {
                // POST_NOTIFICATIONS not granted; onboarding asks for it.
            }
        }

        private fun threadNotificationId(threadId: Long) = MESSAGE_NOTIFICATION_ID_BASE + (threadId % 10_000).toInt()

        companion object {
            const val EXTRA_THREAD_ID = "thread_id"
            private const val MESSAGE_NOTIFICATION_ID_BASE = 20_000
            private const val SCAM_NOTIFICATION_ID_BASE = 40_000
            private const val SEND_FAILURE_NOTIFICATION_ID = 50_001
        }
    }
