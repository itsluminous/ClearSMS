package app.clearsms.receiver

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.net.toUri
import app.clearsms.notification.MessageNotifier
import app.clearsms.sms.TelephonyWriter
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

/**
 * Target of the sent / delivery-report [android.app.PendingIntent]s attached
 * by [app.clearsms.sms.SmsSender].
 *
 * The intent data carries the system provider row uri of the outgoing
 * message so its status can be updated: failed sends are marked
 * `MESSAGE_TYPE_FAILED` (and surfaced to the user), delivery reports set
 * `STATUS_COMPLETE`.
 */
@AndroidEntryPoint
class SmsSentReceiver : BroadcastReceiver() {
    @Inject
    lateinit var telephonyWriter: TelephonyWriter

    @Inject
    lateinit var messageNotifier: MessageNotifier

    override fun onReceive(
        context: Context,
        intent: Intent,
    ) {
        val providerUri = intent.getStringExtra(EXTRA_PROVIDER_URI)?.toUri()
        val destination = intent.getStringExtra(EXTRA_DESTINATION).orEmpty()
        when (intent.action) {
            ACTION_SMS_SENT -> {
                if (resultCode != Activity.RESULT_OK) {
                    providerUri?.let { telephonyWriter.markFailed(it) }
                    messageNotifier.notifySendFailure(destination)
                }
            }
            ACTION_SMS_DELIVERED -> {
                providerUri?.let { telephonyWriter.markDelivered(it) }
            }
        }
    }

    companion object {
        const val ACTION_SMS_SENT = "app.clearsms.action.SMS_SENT"
        const val ACTION_SMS_DELIVERED = "app.clearsms.action.SMS_DELIVERED"
        const val EXTRA_DESTINATION = "destination"
        const val EXTRA_PROVIDER_URI = "provider_uri"
    }
}
