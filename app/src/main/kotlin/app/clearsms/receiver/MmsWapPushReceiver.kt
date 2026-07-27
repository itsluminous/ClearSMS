package app.clearsms.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Telephony
import android.util.Log
import app.clearsms.R
import app.clearsms.data.repository.MessageRepository
import app.clearsms.di.ApplicationScope
import app.clearsms.notification.MessageNotifier
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Handles `WAP_PUSH_DELIVER` (incoming MMS notification for the default app).
 *
 * V1 intentionally does not download MMS content (no transaction with the
 * carrier MMSC): a placeholder message row is stored and a notification is
 * shown so the user knows a multimedia message arrived. Full MMS retrieval
 * is out of scope for this release.
 */
@AndroidEntryPoint
class MmsWapPushReceiver : BroadcastReceiver() {
    @Inject
    lateinit var messageRepository: MessageRepository

    @Inject
    lateinit var messageNotifier: MessageNotifier

    @Inject
    @ApplicationScope
    lateinit var applicationScope: CoroutineScope

    override fun onReceive(
        context: Context,
        intent: Intent,
    ) {
        if (intent.action != Telephony.Sms.Intents.WAP_PUSH_DELIVER_ACTION) return
        val pendingResult = goAsync()
        applicationScope.launch {
            try {
                val entity =
                    messageRepository.insertIncoming(
                        sender = context.getString(R.string.mms_placeholder_sender),
                        body = context.getString(R.string.mms_placeholder_body),
                        timestampMs = System.currentTimeMillis(),
                    )
                if (!entity.isBlockedSender) {
                    messageNotifier.notify(entity)
                }
            } catch (e: Exception) {
                // A storage or notification failure must never crash the
                // process — the default SMS app has to survive every
                // incoming broadcast.
                Log.e(TAG, "Failed to record incoming MMS notification", e)
            } finally {
                pendingResult.finish()
            }
        }
    }

    private companion object {
        const val TAG = "MmsWapPushReceiver"
    }
}
