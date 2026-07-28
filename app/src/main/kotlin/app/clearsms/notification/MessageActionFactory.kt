package app.clearsms.notification

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.RemoteInput
import app.clearsms.R
import app.clearsms.data.db.MessageEntity
import app.clearsms.domain.model.NotificationAction

/**
 * Builds the [NotificationCompat.Action]s for a message-backed notification
 * from a planned [NotificationAction] list (see [NotificationActionPlanner]).
 *
 * All action intents are explicit broadcasts to [MessageActionReceiver]
 * (not exported) with [PendingIntent.FLAG_IMMUTABLE]; the direct-reply
 * action is the single documented [PendingIntent.FLAG_MUTABLE] exception,
 * required for the system to attach the RemoteInput result.
 */
internal object MessageActionFactory {
    fun build(
        context: Context,
        message: MessageEntity,
        notificationId: Int,
        actions: List<NotificationAction>,
    ): List<NotificationCompat.Action> =
        actions.mapNotNull { action ->
            when (action) {
                NotificationAction.MARK_READ ->
                    NotificationCompat.Action
                        .Builder(
                            0,
                            context.getString(R.string.notification_action_mark_read),
                            broadcast(context, message, notificationId, MessageActionReceiver.ACTION_MARK_READ, requestOffset = 0),
                        ).build()
                NotificationAction.DELETE ->
                    NotificationCompat.Action
                        .Builder(
                            0,
                            context.getString(R.string.action_delete),
                            broadcast(context, message, notificationId, MessageActionReceiver.ACTION_DELETE, requestOffset = 1),
                        ).build()
                NotificationAction.REPLY -> replyAction(context, message, notificationId)
                NotificationAction.SHARE -> shareAction(context, message)
                // OTP-only actions are handled by OtpNotifier, never here.
                NotificationAction.COPY_OTP, NotificationAction.SHARE_OTP -> null
            }
        }

    /**
     * Share forwards the message text via a standard ACTION_SEND chooser.
     * The PendingIntent itself is [PendingIntent.FLAG_IMMUTABLE] like every
     * non-reply action; only the chooser target the user picks receives the
     * text.
     */
    private fun shareAction(
        context: Context,
        message: MessageEntity,
    ): NotificationCompat.Action {
        val send =
            Intent(Intent.ACTION_SEND)
                .setType("text/plain")
                .putExtra(Intent.EXTRA_TEXT, message.body)
        val chooser =
            Intent
                .createChooser(send, context.getString(R.string.notification_action_share))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val pending =
            PendingIntent.getActivity(
                context,
                requestCode(message.id, offset = 3),
                chooser,
                NotificationIntents.flags(),
            )
        return NotificationCompat.Action
            .Builder(0, context.getString(R.string.notification_action_share), pending)
            .build()
    }

    private fun replyAction(
        context: Context,
        message: MessageEntity,
        notificationId: Int,
    ): NotificationCompat.Action {
        val remoteInput =
            RemoteInput
                .Builder(MessageActionReceiver.KEY_REPLY_TEXT)
                .setLabel(context.getString(R.string.notification_reply_label))
                .build()
        // FLAG_MUTABLE is required (and safe) here: the system must be able
        // to attach the typed RemoteInput result to the intent. The target
        // receiver is explicit and not exported. Every other action stays
        // immutable.
        val pending =
            PendingIntent.getBroadcast(
                context,
                requestCode(message.id, offset = 2),
                actionIntent(context, message, notificationId, MessageActionReceiver.ACTION_REPLY),
                NotificationIntents.flags(mutable = true),
            )
        return NotificationCompat.Action
            .Builder(0, context.getString(R.string.notification_reply_label), pending)
            .addRemoteInput(remoteInput)
            .setAllowGeneratedReplies(false)
            .build()
    }

    private fun broadcast(
        context: Context,
        message: MessageEntity,
        notificationId: Int,
        action: String,
        requestOffset: Int,
    ): PendingIntent =
        PendingIntent.getBroadcast(
            context,
            requestCode(message.id, requestOffset),
            actionIntent(context, message, notificationId, action),
            NotificationIntents.flags(),
        )

    private fun actionIntent(
        context: Context,
        message: MessageEntity,
        notificationId: Int,
        action: String,
    ): Intent =
        Intent(context, MessageActionReceiver::class.java)
            .setAction(action)
            .putExtra(MessageActionReceiver.EXTRA_MESSAGE_ID, message.id)
            .putExtra(MessageActionReceiver.EXTRA_SENDER, message.sender)
            .putExtra(MessageActionReceiver.EXTRA_NOTIFICATION_ID, notificationId)

    /** Distinct request codes per (message, action) so PendingIntents never collide. */
    private fun requestCode(
        messageId: Long,
        offset: Int,
    ): Int = ((messageId % 100_000) * 8 + offset).toInt()
}
