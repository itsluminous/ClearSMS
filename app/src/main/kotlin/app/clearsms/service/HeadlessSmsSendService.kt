package app.clearsms.service

import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.telephony.TelephonyManager
import app.clearsms.di.ApplicationScope
import app.clearsms.sms.SmsSender
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Handles `RESPOND_VIA_MESSAGE` — the "reply with message" quick action the
 * dialer sends when the user declines an incoming call with a canned text.
 * Required for default-SMS-app eligibility.
 */
@AndroidEntryPoint
class HeadlessSmsSendService : Service() {
    @Inject
    lateinit var smsSender: SmsSender

    @Inject
    @ApplicationScope
    lateinit var applicationScope: CoroutineScope

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int,
    ): Int {
        if (intent?.action == TelephonyManager.ACTION_RESPOND_VIA_MESSAGE) {
            val destination = intent.data?.schemeSpecificPart
            val body = intent.getStringExtra(Intent.EXTRA_TEXT)
            if (!destination.isNullOrBlank() && !body.isNullOrBlank()) {
                applicationScope.launch {
                    try {
                        smsSender.send(destination, body)
                    } finally {
                        stopSelf(startId)
                    }
                }
                return START_NOT_STICKY
            }
        }
        stopSelf(startId)
        return START_NOT_STICKY
    }
}
