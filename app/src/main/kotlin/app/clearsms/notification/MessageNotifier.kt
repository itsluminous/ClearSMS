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
import app.clearsms.domain.model.NotificationAction
import app.clearsms.mms.MmsSnippet
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
        private val senderResolver: NotificationSenderResolver,
        private val iconFactory: SenderIconFactory,
    ) {
        /**
         * Posts / updates the notification for [message]'s thread.
         *
         * The sender is resolved through the same chain the UI uses (contact
         * name + photo → sender-ID directory → curated brand table → raw
         * address); a denied READ_CONTACTS or any lookup failure degrades to
         * the raw address. Callers invoke this off the main thread (the
         * receiver's IO application scope), so the cached contact lookup never
         * blocks UI. No shortcut/bubble APIs are used, so the [Person] built
         * here is the only conversation identity to keep consistent.
         *
         * [selected] is the user's notification-action choice (defaults to
         * the settings default for callers without settings access). REPLY
         * is offered only for repliable
         * addresses - see [NotificationActionPlanner.isRepliableAddress].
         */
        fun notify(
            message: MessageEntity,
            selected: Set<NotificationAction> = DEFAULT_SELECTED,
            channelId: String = Channels.MESSAGES,
        ) {
            Channels.ensureCreated(context)
            val resolved = senderResolver.resolve(message.sender)
            // An image-only MMS has no body text; the shared snippet helper
            // labels it ("📷 Photo") the same way the inbox row does.
            val displayBody = MmsSnippet.overrideRes(message)?.let(context::getString) ?: message.body
            val sender =
                Person
                    .Builder()
                    .setName(resolved.name)
                    .setKey(message.normalizedSender)
                    .setIcon(iconFactory.iconFor(resolved))
                    .build()
            val style =
                NotificationCompat
                    .MessagingStyle(Person.Builder().setName(context.getString(R.string.notification_me)).build())
                    .addMessage(displayBody, message.timestamp, sender)
            val notificationId = threadNotificationId(message.threadId)
            val planned =
                NotificationActionPlanner.forMessage(
                    selected,
                    repliable = NotificationActionPlanner.isRepliableAddress(message.sender),
                )
            val builder =
                NotificationCompat
                    .Builder(context, channelId)
                    .setSmallIcon(R.drawable.ic_notification)
                    .setContentTitle(resolved.name)
                    .setContentText(displayBody)
                    .setStyle(style)
                    .setCategory(NotificationCompat.CATEGORY_MESSAGE)
                    .setContentIntent(conversationIntent(message.threadId))
                    .setAutoCancel(true)
            MessageActionFactory.build(context, message, notificationId, planned).forEach(builder::addAction)
            post(notificationId, builder.build())
        }

        /** High-priority warning for a message flagged as a likely scam. */
        fun notifyScam(message: MessageEntity) {
            Channels.ensureCreated(context)
            val resolved = senderResolver.resolve(message.sender)
            val notification =
                NotificationCompat
                    .Builder(context, Channels.SECURITY)
                    .setSmallIcon(R.drawable.ic_notification)
                    .setLargeIcon(iconFactory.largeIconFor(resolved))
                    .setContentTitle(context.getString(R.string.scam_warning_title))
                    .setContentText(context.getString(R.string.scam_warning_text, resolved.name))
                    .setStyle(NotificationCompat.BigTextStyle().bigText(message.body))
                    .setPriority(NotificationCompat.PRIORITY_HIGH)
                    .setCategory(NotificationCompat.CATEGORY_MESSAGE)
                    .setContentIntent(conversationIntent(message.threadId))
                    .setAutoCancel(true)
                    .build()
            post(NotificationIds.scam(message.id), notification)
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
            post(NotificationIds.SEND_FAILURE, notification)
        }

        private fun conversationIntent(threadId: Long): PendingIntent {
            // Explicit component (class-name string avoids a compile-time UI dependency):
            // an implicit VIEW intent could be intercepted by another app claiming
            // the clearsms scheme.
            val intent =
                Intent(Intent.ACTION_VIEW, "clearsms://conversation/$threadId".toUri())
                    .setClassName(context, "app.clearsms.MainActivity")
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

        private fun threadNotificationId(threadId: Long) = NotificationIds.messageThread(threadId)

        companion object {
            const val EXTRA_THREAD_ID = "thread_id"

            /** Mirrors the settings default (MARK_READ + REPLY). */
            val DEFAULT_SELECTED = setOf(NotificationAction.MARK_READ, NotificationAction.REPLY)
        }
    }
