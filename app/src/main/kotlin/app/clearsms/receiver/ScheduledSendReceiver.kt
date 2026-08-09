package app.clearsms.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import app.clearsms.di.ApplicationScope
import app.clearsms.work.MessageScheduler
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * AlarmManager target for scheduled sends: fires the message through the
 * normal dispatch path. NOT exported - only our own PendingIntents (armed by
 * [app.clearsms.work.ScheduledSendAlarms]) may trigger a send.
 */
@AndroidEntryPoint
class ScheduledSendReceiver : BroadcastReceiver() {
    @Inject
    lateinit var messageScheduler: MessageScheduler

    @Inject
    @ApplicationScope
    lateinit var applicationScope: CoroutineScope

    override fun onReceive(
        context: Context,
        intent: Intent,
    ) {
        if (intent.action != ACTION_SEND_SCHEDULED) return
        val messageId = intent.getLongExtra(EXTRA_MESSAGE_ID, -1L)
        if (messageId < 0) return
        val pendingResult = goAsync()
        applicationScope.launch {
            try {
                messageScheduler.fire(messageId)
            } finally {
                pendingResult.finish()
            }
        }
    }

    companion object {
        const val ACTION_SEND_SCHEDULED = "app.clearsms.action.SEND_SCHEDULED"
        const val EXTRA_MESSAGE_ID = "message_id"
    }
}
