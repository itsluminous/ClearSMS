package app.clearsms.receiver

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.telephony.SmsManager
import android.telephony.SubscriptionManager
import app.clearsms.data.db.DeliveryStatus
import app.clearsms.data.db.MessageDao
import app.clearsms.diagnostics.Diag
import app.clearsms.diagnostics.DiagField.Companion.code
import app.clearsms.diagnostics.DiagField.Companion.count
import app.clearsms.diagnostics.DiagField.Companion.flag
import app.clearsms.diagnostics.DiagField.Companion.id
import app.clearsms.diagnostics.DiagField.Companion.label
import app.clearsms.mms.AttachmentStore
import app.clearsms.mms.DataSim
import app.clearsms.mms.SendFailureReason
import app.clearsms.mms.TriState
import app.clearsms.sms.SimSelector
import app.clearsms.sms.SubscriptionSource
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
 *
 * Every report is logged through [Diag] with the RAW platform result code
 * (the mapped [SendFailureReason] folds several codes into one reason, so
 * the code is the datum a bug report needs), the HTTP status the platform
 * attaches to an MMSC refusal, and whether a send-conf PDU came back.
 * A failure line also carries the sending SIM's slot and whether it is
 * the phone's mobile-data SIM (see [app.clearsms.mms.DataSim]) - slots
 * and yes/no only. The destination extra is read for the failure
 * notification only and never logged.
 */
@AndroidEntryPoint
class MmsSentReceiver : BroadcastReceiver() {
    @Inject
    lateinit var recorder: MmsSendReportRecorder

    @Inject
    lateinit var subscriptionSource: SubscriptionSource

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
        MmsSendReport.of(intent, resultCode, failureReason, subscriptionSource).log(messageId)
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

        /** The subscription the send was handed over with; [SubscriptionManager.INVALID_SUBSCRIPTION_ID] for the system default. */
        const val EXTRA_SUBSCRIPTION_ID = "subscription_id"

        private val receiverScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    }
}

/**
 * The loggable shape of one platform MMS send result: the raw code, its
 * mapped reason, and the two extras [SmsManager] documents on the sent
 * intent - [SmsManager.EXTRA_MMS_HTTP_STATUS] (the MMSC's HTTP status,
 * present on an HTTP failure) and [SmsManager.EXTRA_MMS_DATA] (the
 * `m-send-conf` PDU, present on success; only its LENGTH is kept - the
 * PDU carries the carrier's message id, which is nobody's business in a
 * shared log). Pure, so the receiver's logging is unit-testable.
 */
data class MmsSendReport(
    val resultCode: Int,
    val reason: SendFailureReason?,
    val httpStatus: Int?,
    val sendConfBytes: Int?,
    /** 1-based slot of the sending subscription; null when unknown or system default. */
    val slot: Int? = null,
    /** Whether the sending subscription is the default data subscription; null when unknown. */
    val onDataSim: Boolean? = null,
) {
    val succeeded: Boolean get() = resultCode == Activity.RESULT_OK

    /**
     * Records this report. Success and failure are separate events (not
     * one event with a flag) so a failure reads as a WARN line; both carry
     * the message id and the raw code. The extras travel as presence flags
     * plus their numeric value - never the PDU bytes.
     */
    fun log(messageId: Long) {
        if (succeeded) {
            Diag.i(
                TAG,
                "mms sent",
                id("message", messageId),
                code("result", resultCode),
                flag("sendConf", sendConfBytes != null),
                count("sendConfBytes", sendConfBytes ?: 0),
            )
        } else {
            Diag.w(
                TAG,
                "mms send failed",
                null,
                id("message", messageId),
                code("result", resultCode),
                label("reason", reason),
                flag("httpStatusPresent", httpStatus != null),
                code("httpStatus", httpStatus ?: 0),
                count("slot", slot ?: 0),
                label("onDataSim", TriState.of(onDataSim)),
            )
        }
    }

    companion object {
        private const val TAG = "MmsSendReport"

        /**
         * Builds the report from the sent intent. With a [subscriptions]
         * source the sending SIM (the [MmsSentReceiver.EXTRA_SUBSCRIPTION_ID]
         * extra, or the default SMS subscription for a system-default send)
         * is placed in its slot and compared with the default data
         * subscription; any platform failure there reads as unknown.
         */
        fun of(
            intent: Intent,
            resultCode: Int,
            reason: SendFailureReason?,
            subscriptions: SubscriptionSource? = null,
        ): MmsSendReport {
            val sending =
                intent
                    .getIntExtra(MmsSentReceiver.EXTRA_SUBSCRIPTION_ID, SubscriptionManager.INVALID_SUBSCRIPTION_ID)
                    .takeIf { it != SubscriptionManager.INVALID_SUBSCRIPTION_ID }
            val sims = runCatching { subscriptions?.activeSims() }.getOrNull().orEmpty()
            val data = runCatching { subscriptions?.defaultDataSubscriptionId() }.getOrNull()
            val effectiveSending = sending ?: runCatching { subscriptions?.defaultSmsSubscriptionId() }.getOrNull()
            return MmsSendReport(
                resultCode = resultCode,
                reason = reason,
                httpStatus =
                    if (intent.hasExtra(SmsManager.EXTRA_MMS_HTTP_STATUS)) {
                        intent.getIntExtra(SmsManager.EXTRA_MMS_HTTP_STATUS, 0)
                    } else {
                        null
                    },
                sendConfBytes = intent.getByteArrayExtra(SmsManager.EXTRA_MMS_DATA)?.size,
                slot = SimSelector.slotNumberFor(sims, sending),
                onDataSim = if (subscriptions == null) null else DataSim.sendsOnDataSim(effectiveSending, data),
            )
        }
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
                if (current == null) {
                    // The row vanished (deleted while sending): nothing to
                    // mark, but a report with no home is worth a trace.
                    Diag.w(TAG, "mms send report for missing message", null, id("message", messageId))
                } else if (current.deliveryStatus != DeliveryStatus.FAILED) {
                    messageDao.setDeliveryStatus(messageId, DeliveryStatus.FAILED)
                    messageDao.setSendFailureReason(messageId, failureReason?.name)
                    sideEffects.notifyFailure(destination, current.threadId, messageId)
                }
            }
            attachmentStore.stagingFile(messageId).delete()
        }

        private companion object {
            const val TAG = "MmsSendReport"
        }
    }
