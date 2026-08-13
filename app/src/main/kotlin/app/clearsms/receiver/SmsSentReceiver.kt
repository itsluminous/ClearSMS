package app.clearsms.receiver

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.net.toUri
import app.clearsms.data.db.DeliveryStatus
import app.clearsms.data.db.MessageDao
import app.clearsms.notification.MessageNotifier
import app.clearsms.sms.TelephonyWriter
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Target of the per-part sent / delivery-report [android.app.PendingIntent]s
 * attached by [app.clearsms.sms.SmsSender]. Each intent carries the part
 * index and total part count; the actual aggregation lives in
 * [SendReportRecorder] so the worst-part rules are unit-testable without
 * broadcasting anything.
 */
@AndroidEntryPoint
class SmsSentReceiver : BroadcastReceiver() {
    @Inject
    lateinit var recorder: SendReportRecorder

    override fun onReceive(
        context: Context,
        intent: Intent,
    ) {
        val report =
            SendPartReport(
                status = SendReportMapper.statusFor(intent.action, resultCode == Activity.RESULT_OK) ?: return,
                providerUri = intent.getStringExtra(EXTRA_PROVIDER_URI)?.toUri(),
                destination = intent.getStringExtra(EXTRA_DESTINATION).orEmpty(),
                partIndex = intent.getIntExtra(EXTRA_PART_INDEX, 0),
                partCount = intent.getIntExtra(EXTRA_PART_COUNT, 1),
            )
        val pending = goAsync()
        receiverScope.launch {
            try {
                recorder.record(report)
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        const val ACTION_SMS_SENT = "app.clearsms.action.SMS_SENT"
        const val ACTION_SMS_DELIVERED = "app.clearsms.action.SMS_DELIVERED"
        const val EXTRA_DESTINATION = "destination"
        const val EXTRA_PROVIDER_URI = "provider_uri"
        const val EXTRA_PART_INDEX = "part_index"
        const val EXTRA_PART_COUNT = "part_count"

        private val receiverScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    }
}

/** One radio report for one part of an outgoing message. */
data class SendPartReport(
    val status: DeliveryStatus,
    val providerUri: Uri?,
    val destination: String,
    val partIndex: Int,
    val partCount: Int,
)

/**
 * The non-database consequences of a radio report - provider-row mirroring
 * and the user-facing failure notification - behind a seam so the worst-part
 * aggregation in [SendReportRecorder] is unit-testable.
 */
interface SendReportSideEffects {
    fun mirrorFailed(providerUri: Uri)

    fun mirrorDelivered(providerUri: Uri)

    fun notifyFailure(
        destination: String,
        threadId: Long? = null,
        messageId: Long? = null,
    )
}

/** Production side effects: system SMS provider columns + failure notification. */
@Singleton
class DefaultSendReportSideEffects
    @Inject
    constructor(
        private val telephonyWriter: TelephonyWriter,
        private val messageNotifier: MessageNotifier,
    ) : SendReportSideEffects {
        override fun mirrorFailed(providerUri: Uri) = telephonyWriter.markFailed(providerUri)

        override fun mirrorDelivered(providerUri: Uri) = telephonyWriter.markDelivered(providerUri)

        override fun notifyFailure(
            destination: String,
            threadId: Long?,
            messageId: Long?,
        ) = messageNotifier.notifySendFailure(destination, threadId, messageId)
    }

/**
 * Applies a per-part radio report to the outgoing message's persisted
 * [DeliveryStatus] (worst-part semantics) and mirrors terminal transitions to
 * the system provider row:
 *
 * - FAILED (any part): the whole message fails, overwriting SENT/DELIVERED -
 *   a message with a lost part was not delivered. Provider row is marked
 *   `MESSAGE_TYPE_FAILED` and the user is notified exactly once per message
 *   even when several parts fail.
 * - SENT: only the LAST part's OK report promotes SENDING → SENT
 *   (compare-and-set). The radio hands parts over sequentially, so
 *   last-part-OK means every earlier part was handed over too - unless one
 *   already failed, in which case the row is FAILED and the promote is a
 *   no-op. This is the honest granularity `sendMultipartTextMessage` offers
 *   for "sent" without per-part persistence.
 * - DELIVERED: counted per part; the message is promoted to DELIVERED only
 *   when EVERY part has a carrier delivery report and no part has failed.
 *   `STATUS_COMPLETE` is mirrored to the provider only on that completing
 *   report. A partially delivered multipart message stays at SENT.
 */
@Singleton
class SendReportRecorder
    @Inject
    constructor(
        private val messageDao: MessageDao,
        private val sideEffects: SendReportSideEffects,
    ) {
        suspend fun record(report: SendPartReport) {
            val systemSmsId = report.providerUri?.lastPathSegment?.toLongOrNull()
            when (report.status) {
                DeliveryStatus.FAILED -> {
                    val newlyFailed =
                        if (systemSmsId != null) messageDao.markFailedBySystemId(systemSmsId) > 0 else true
                    if (newlyFailed) {
                        report.providerUri?.let { sideEffects.mirrorFailed(it) }
                        val row = systemSmsId?.let { messageDao.getBySystemId(it) }
                        sideEffects.notifyFailure(report.destination, row?.threadId, row?.id)
                    }
                }
                DeliveryStatus.SENT -> {
                    if (systemSmsId != null && report.partIndex == report.partCount - 1) {
                        messageDao.promoteDeliveryStatusBySystemId(
                            systemSmsId,
                            expected = DeliveryStatus.SENDING,
                            newStatus = DeliveryStatus.SENT,
                        )
                    }
                }
                DeliveryStatus.DELIVERED -> {
                    if (systemSmsId != null && messageDao.recordPartDelivered(systemSmsId)) {
                        report.providerUri?.let { sideEffects.mirrorDelivered(it) }
                    }
                }
                DeliveryStatus.SENDING, DeliveryStatus.SCHEDULED -> Unit
            }
        }
    }

/** Pure mapping from a radio report to the [DeliveryStatus] it records. */
object SendReportMapper {
    fun statusFor(
        action: String?,
        resultOk: Boolean,
    ): DeliveryStatus? =
        when (action) {
            SmsSentReceiver.ACTION_SMS_SENT ->
                if (resultOk) DeliveryStatus.SENT else DeliveryStatus.FAILED
            SmsSentReceiver.ACTION_SMS_DELIVERED -> DeliveryStatus.DELIVERED
            else -> null
        }
}
