package app.clearsms.receiver

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import app.clearsms.data.db.DeliveryStatus
import app.clearsms.data.db.MessageDao
import app.clearsms.mms.AttachmentStore
import app.clearsms.mms.SendFailureReason
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Result target of [android.telephony.SmsManager.sendMultimediaMessage]
 * (see [app.clearsms.mms.MmsSender]). NOT exported: only our own
 * PendingIntents may report a send outcome. The single result maps onto
 * the existing outgoing lifecycle: RESULT_OK promotes SENDING -> SENT,
 * anything else marks FAILED (tap -> Retry, like a failed SMS). MMS has
 * no per-part reports and - this wave - no delivery reports, so there is
 * no DELIVERED transition.
 */
@AndroidEntryPoint
class MmsSentReceiver : BroadcastReceiver() {
    @Inject
    lateinit var recorder: MmsSendReportRecorder

    override fun onReceive(
        context: Context,
        intent: Intent,
    ) {
        if (intent.action != ACTION_MMS_SENT) return
        val messageId = intent.getLongExtra(EXTRA_MESSAGE_ID, -1L)
        if (messageId < 0) return
        val destination = intent.getStringExtra(EXTRA_DESTINATION).orEmpty()
        val succeeded = resultCode == Activity.RESULT_OK
        val failureReason =
            if (succeeded) null else SendFailureReason.fromMmsResultCode(resultCode)
        val pending = goAsync()
        receiverScope.launch {
            try {
                recorder.record(messageId, destination, succeeded, failureReason)
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        const val ACTION_MMS_SENT = "app.clearsms.action.MMS_SENT"
        const val EXTRA_MESSAGE_ID = "message_id"
        const val EXTRA_DESTINATION = "destination"

        private val receiverScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    }
}

/**
 * Applies the platform's MMS send result to the message's persisted
 * [DeliveryStatus]. Success promotes with a compare-and-set (a failure
 * recorded meanwhile wins); failure sets FAILED and notifies through the
 * same [SendReportSideEffects] seam SMS failures use. Either way the
 * staged PDU file has served its purpose and is deleted.
 */
@Singleton
class MmsSendReportRecorder
    @Inject
    constructor(
        private val messageDao: MessageDao,
        private val attachmentStore: AttachmentStore,
        private val sideEffects: SendReportSideEffects,
    ) {
        suspend fun record(
            messageId: Long,
            destination: String,
            succeeded: Boolean,
            failureReason: SendFailureReason? = null,
        ) {
            if (succeeded) {
                messageDao.promoteDeliveryStatus(
                    messageId,
                    expected = DeliveryStatus.SENDING,
                    newStatus = DeliveryStatus.SENT,
                )
            } else {
                val current = messageDao.getById(messageId)
                if (current != null && current.deliveryStatus != DeliveryStatus.FAILED) {
                    messageDao.setDeliveryStatus(messageId, DeliveryStatus.FAILED)
                    messageDao.setSendFailureReason(messageId, failureReason?.name)
                    sideEffects.notifyFailure(destination)
                }
            }
            attachmentStore.stagingFile(messageId).delete()
        }
    }
