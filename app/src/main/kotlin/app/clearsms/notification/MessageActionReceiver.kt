package app.clearsms.notification

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.RemoteInput
import app.clearsms.data.repository.MessageRepository
import app.clearsms.data.repository.UndoManager
import app.clearsms.di.ApplicationScope
import app.clearsms.sms.SmsSender
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Handles the Mark read / Delete / Reply actions on message and transaction
 * notifications.
 *
 * SECURITY: this receiver is deliberately NOT exported (see the manifest) —
 * a third-party app must never be able to spoof a delete or mark-read for an
 * arbitrary message. All action intents are explicit and created in-process.
 */
@AndroidEntryPoint
class MessageActionReceiver : BroadcastReceiver() {
    @Inject
    lateinit var messageRepository: MessageRepository

    @Inject
    lateinit var undoManager: UndoManager

    @Inject
    lateinit var smsSender: SmsSender

    @Inject
    @ApplicationScope
    lateinit var applicationScope: CoroutineScope

    override fun onReceive(
        context: Context,
        intent: Intent,
    ) {
        val messageId = intent.getLongExtra(EXTRA_MESSAGE_ID, -1L)
        val notificationId = intent.getIntExtra(EXTRA_NOTIFICATION_ID, -1)
        if (messageId <= 0L) return

        fun dismiss() {
            if (notificationId != -1) NotificationManagerCompat.from(context).cancel(notificationId)
        }

        when (intent.action) {
            ACTION_MARK_READ -> {
                val pendingResult = goAsync()
                applicationScope.launch {
                    try {
                        messageRepository.markRead(messageId)
                        dismiss()
                    } finally {
                        pendingResult.finish()
                    }
                }
            }
            ACTION_DELETE -> {
                val pendingResult = goAsync()
                applicationScope.launch {
                    try {
                        undoManager.deleteNow(listOf(messageId))
                        dismiss()
                    } finally {
                        pendingResult.finish()
                    }
                }
            }
            ACTION_REPLY -> {
                val destination = intent.getStringExtra(EXTRA_SENDER) ?: return
                val text =
                    RemoteInput
                        .getResultsFromIntent(intent)
                        ?.getCharSequence(KEY_REPLY_TEXT)
                        ?.toString()
                        ?.trim()
                        .orEmpty()
                val pendingResult = goAsync()
                applicationScope.launch {
                    try {
                        if (text.isNotEmpty()) {
                            smsSender.send(destination, text)
                            messageRepository.markRead(messageId)
                        }
                        // The shade shows a spinner until the notification is
                        // updated; cancelling it is the update.
                        dismiss()
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to send reply", e)
                        dismiss()
                    } finally {
                        pendingResult.finish()
                    }
                }
            }
        }
    }

    companion object {
        const val ACTION_MARK_READ = "app.clearsms.action.MESSAGE_MARK_READ"
        const val ACTION_DELETE = "app.clearsms.action.MESSAGE_DELETE"
        const val ACTION_REPLY = "app.clearsms.action.MESSAGE_REPLY"
        const val EXTRA_MESSAGE_ID = "message_id"
        const val EXTRA_SENDER = "sender"
        const val EXTRA_NOTIFICATION_ID = "notification_id"

        /** RemoteInput result key for the inline reply text. */
        const val KEY_REPLY_TEXT = "reply_text"

        private const val TAG = "MessageActionReceiver"
    }
}
