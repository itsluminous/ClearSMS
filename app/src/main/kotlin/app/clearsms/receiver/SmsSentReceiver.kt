package app.clearsms.receiver

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
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

/**
 * Target of the sent / delivery-report [android.app.PendingIntent]s attached
 * by [app.clearsms.sms.SmsSender].
 *
 * The intent data carries the system provider row uri of the outgoing
 * message, whose id is also the Room row's `systemSmsId` — so each radio
 * report is recorded BOTH places: the provider row (failed sends become
 * `MESSAGE_TYPE_FAILED`, delivery reports set `STATUS_COMPLETE`) and the
 * local message's persisted [DeliveryStatus], which the conversation UI
 * renders. A sent-OK report only promotes SENDING → SENT (compare-and-set),
 * so a late one can never downgrade an already-DELIVERED message.
 */
@AndroidEntryPoint
class SmsSentReceiver : BroadcastReceiver() {
    @Inject
    lateinit var telephonyWriter: TelephonyWriter

    @Inject
    lateinit var messageNotifier: MessageNotifier

    @Inject
    lateinit var messageDao: MessageDao

    override fun onReceive(
        context: Context,
        intent: Intent,
    ) {
        val providerUri = intent.getStringExtra(EXTRA_PROVIDER_URI)?.toUri()
        val systemSmsId = providerUri?.lastPathSegment?.toLongOrNull()
        val destination = intent.getStringExtra(EXTRA_DESTINATION).orEmpty()
        val status = SendReportMapper.statusFor(intent.action, resultCode == Activity.RESULT_OK) ?: return

        when (status) {
            DeliveryStatus.FAILED -> {
                providerUri?.let { telephonyWriter.markFailed(it) }
                messageNotifier.notifySendFailure(destination)
            }
            DeliveryStatus.DELIVERED -> providerUri?.let { telephonyWriter.markDelivered(it) }
            else -> Unit
        }

        if (systemSmsId == null) return
        val pending = goAsync()
        receiverScope.launch {
            try {
                when (status) {
                    DeliveryStatus.SENT ->
                        messageDao.promoteDeliveryStatusBySystemId(
                            systemSmsId,
                            expected = DeliveryStatus.SENDING,
                            newStatus = DeliveryStatus.SENT,
                        )
                    else -> messageDao.setDeliveryStatusBySystemId(systemSmsId, status)
                }
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

        private val receiverScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
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
