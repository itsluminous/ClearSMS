package app.clearsms.notification

import android.app.PendingIntent
import android.content.Context
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.Person
import androidx.core.content.LocusIdCompat
import app.clearsms.ConversationDeepLink
import app.clearsms.R
import app.clearsms.data.db.MessageEntity
import app.clearsms.domain.model.NotificationAction
import app.clearsms.domain.model.StartDestination
import app.clearsms.mms.MmsSnippet
import app.clearsms.shortcuts.ConversationShortcutRegistry
import app.clearsms.shortcuts.ConversationShortcutSelection
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Notifications for regular incoming messages and security warnings.
 *
 * One [NotificationCompat.MessagingStyle] notification per thread; tapping it
 * deep-links into the conversation via a
 * `clearsms://conversation/<threadId>?messageId=<messageId>` uri handled by
 * the main activity's navigation graph, which scrolls to and briefly
 * highlights the message the notification was about (the same wash a search
 * result gets).
 *
 * On Android 11+ the notification is a *conversation notification*: it names
 * the thread's long-lived shortcut (`setShortcutId`), so the system files it
 * in the Conversations section and offers its per-conversation controls.
 * Bubbles are NOT implemented: they would need `BubbleMetadata` with a
 * dedicated conversation activity that is `resizeableActivity`,
 * `documentLaunchMode="always"` and `allowEmbedded`, plus a floating-window
 * conversation UI - none of which exist here yet.
 */
@Singleton
class MessageNotifier
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
        private val senderResolver: NotificationSenderResolver,
        private val iconFactory: SenderIconFactory,
        private val sectionGate: NotificationSectionGate,
        private val mutedSenderGate: MutedSenderGate,
        /**
         * Which conversation shortcuts exist right now; defaults to "none",
         * the exact pre-feature behaviour and what every device below API 25
         * lives with. Tests that do not care about shortcuts omit it.
         */
        private val shortcuts: ConversationShortcutRegistry = ConversationShortcutRegistry { false },
    ) {
        /**
         * Posts / updates the notification for [message]'s thread.
         *
         * The sender is resolved through the same chain the UI uses (contact
         * name + photo → sender-ID directory → curated brand table → raw
         * address); a denied READ_CONTACTS or any lookup failure degrades to
         * the raw address. Callers invoke this off the main thread (the
         * receiver's IO application scope), so the cached contact lookup never
         * blocks UI. The [Person] built here is one of two conversation
         * identities the app publishes - the other is the shortcut
         * [app.clearsms.shortcuts.ConversationShortcutPublisher] keys by the
         * same app thread id, carrying a Person with the same key - and both
         * resolve the name and icon through this same resolver/icon chain,
         * so they can never disagree. When that shortcut is published the
         * notification names it (`setShortcutId`), which is what makes it an
         * Android 11 conversation notification; see [build] for how a
         * missing shortcut is handled. No bubble API is used here.
         *
         * [selected] is the user's notification-action choice (defaults to
         * the settings default for callers without settings access). REPLY
         * is offered only for repliable
         * addresses - see [NotificationActionPlanner.isRepliableAddress].
         */
        suspend fun notify(
            message: MessageEntity,
            selected: Set<NotificationAction> = DEFAULT_SELECTED,
            channelId: String = Channels.MESSAGES,
        ) {
            // A message notification is the Inbox section's voice: silent
            // while the user has switched that whole section off.
            if (!sectionGate.allows(StartDestination.INBOX)) return
            // ...and silent for a sender the user muted. The router already
            // decided this; the check here catches any caller around it.
            if (!mutedSenderGate.allows(message.sender)) return
            Channels.ensureCreated(context)
            post(threadNotificationId(message.threadId), build(message, selected, channelId))
        }

        /**
         * Builds the notification; internal so tests can inspect it without
         * posting.
         *
         * **A missing shortcut never costs a notification.** The id is set
         * only when [ConversationShortcutRegistry] says the thread's shortcut
         * is published - an in-memory read, no settings or system call, so
         * this path never suspends. When it is not (setting off, outside
         * the budget, excluded thread, rate-limited publish, API < 25), the
         * notification is built exactly as before this feature: a plain
         * MessagingStyle notification in the app's channel. And should the
         * shortcut vanish between the check and the post, the platform is
         * lenient by design: `NotificationManagerService.enqueueNotificationInternal`
         * resolves the id via `ShortcutHelper.getValidShortcutInfo`, and on
         * null it only logs "added an invalid shortcut", clears the record's
         * shortcut and continues to enqueue - the notification posts as a
         * non-conversation one, nothing is dropped or delayed (verified in
         * AOSP android11-release and main). The LocusId mirrors the shortcut,
         * as the platform recommends, so the system can tie the two.
         */
        internal fun build(
            message: MessageEntity,
            selected: Set<NotificationAction> = DEFAULT_SELECTED,
            channelId: String = Channels.MESSAGES,
        ): android.app.Notification {
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
                    // The tap highlights the message the notification is
                    // about - the NEWEST one. FLAG_UPDATE_CURRENT on the
                    // per-thread request code keeps the single thread
                    // notification's target current as messages arrive.
                    .setContentIntent(
                        conversationIntent(
                            message.threadId,
                            message.id,
                            requestCode = threadNotificationId(message.threadId),
                        ),
                    ).setAutoCancel(true)
            if (shortcuts.isPublished(message.threadId)) {
                val shortcutId = ConversationShortcutSelection.shortcutId(message.threadId)
                builder.setShortcutId(shortcutId).setLocusId(LocusIdCompat(shortcutId))
            }
            MessageActionFactory.build(context, message, notificationId, planned).forEach(builder::addAction)
            return builder.build()
        }

        /**
         * High-priority warning for a message flagged as a likely scam. A
         * scam warning is still a notification ABOUT an incoming message -
         * an inbox surface - so it follows the Inbox flag like the plain
         * notification (the message itself stays flagged in-app either way).
         *
         * Deliberately NOT mute-gated: a muted sender that starts phishing is
         * exactly when the warning matters, and muting is a request for
         * quiet, not for being left defenceless (see [MutedSenderGate]).
         */
        suspend fun notifyScam(message: MessageEntity) {
            if (!sectionGate.allows(StartDestination.INBOX)) return
            Channels.ensureCreated(context)
            post(NotificationIds.scam(message.id), buildScam(message))
        }

        /** Builds the scam warning; internal so tests can inspect it without posting. */
        internal fun buildScam(message: MessageEntity): android.app.Notification {
            val resolved = senderResolver.resolve(message.sender)
            return NotificationCompat
                .Builder(context, Channels.SECURITY)
                .setSmallIcon(R.drawable.ic_notification)
                .setLargeIcon(iconFactory.largeIconFor(resolved))
                .setContentTitle(context.getString(R.string.scam_warning_title))
                .setContentText(context.getString(R.string.scam_warning_text, resolved.name))
                .setStyle(NotificationCompat.BigTextStyle().bigText(message.body))
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setCategory(NotificationCompat.CATEGORY_MESSAGE)
                // The scam warning is per-message and coexists in the shade
                // with the same thread's plain notification, so it MUST NOT
                // share that notification's per-thread request code - with
                // FLAG_UPDATE_CURRENT the two taps would collapse onto one
                // PendingIntent and highlight the same message. The scam id
                // space (NotificationIds.scam) is disjoint from every other
                // notifier's, so it doubles as the request-code space.
                .setContentIntent(
                    conversationIntent(
                        message.threadId,
                        message.id,
                        requestCode = NotificationIds.scam(message.id),
                    ),
                ).setAutoCancel(true)
                .build()
        }

        /**
         * Shown when an outgoing message could not be sent. Deliberately NOT
         * section-gated: this is feedback about the user's OWN action, not
         * incoming-message noise - suppressing it while Inbox is off would
         * silently lose a failed send.
         */
        fun notifySendFailure(
            destination: String,
            threadId: Long? = null,
            messageId: Long? = null,
        ) {
            Channels.ensureCreated(context)
            val builder =
                NotificationCompat
                    .Builder(context, Channels.MESSAGES)
                    .setSmallIcon(R.drawable.ic_notification)
                    .setContentTitle(context.getString(R.string.send_failed_title))
                    .setContentText(context.getString(R.string.send_failed_text, destination))
                    .setCategory(NotificationCompat.CATEGORY_ERROR)
                    .setAutoCancel(true)
            // Tap opens the conversation scrolled to (and highlighting) the
            // failed message, where the bubble's tap offers Retry/Delete.
            if (threadId != null) {
                builder.setContentIntent(failedMessageIntent(threadId, messageId))
            }
            post(NotificationIds.SEND_FAILURE, builder.build())
        }

        private fun failedMessageIntent(
            threadId: Long,
            messageId: Long?,
        ): PendingIntent {
            val intent =
                ConversationDeepLink
                    .intent(context, threadId, messageId)
                    .putExtra(EXTRA_THREAD_ID, threadId)
            return PendingIntent.getActivity(
                context,
                // Distinct request-code space from conversationIntent so a
                // failure intent never recycles a plain-open intent.
                (messageId ?: threadId).toInt() or 0x40000000,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        }

        /**
         * Deep link opening the conversation scrolled to (and highlighting)
         * [messageId] - the same `?messageId=` shape [failedMessageIntent],
         * [OtpNotifier] and [TransactionNotifier] use, resolved by the nav
         * graph into [app.clearsms.ui.conversation.ConversationViewModel]'s
         * highlight target.
         *
         * [requestCode] MUST come from the caller's own [NotificationIds]
         * band: those bands are disjoint by construction, so two live
         * notifications about different messages can never collapse onto one
         * FLAG_UPDATE_CURRENT PendingIntent (which would send both taps to
         * whichever message was posted last).
         */
        private fun conversationIntent(
            threadId: Long,
            messageId: Long,
            requestCode: Int,
        ): PendingIntent {
            // The shared explicit intent (ConversationDeepLink): the same
            // one a launcher shortcut fires, so a notification tap and a
            // shortcut tap can never navigate differently.
            val intent =
                ConversationDeepLink
                    .intent(context, threadId, messageId)
                    .putExtra(EXTRA_THREAD_ID, threadId)
                    .putExtra(MessageActionReceiver.EXTRA_MESSAGE_ID, messageId)
            return PendingIntent.getActivity(
                context,
                requestCode,
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
