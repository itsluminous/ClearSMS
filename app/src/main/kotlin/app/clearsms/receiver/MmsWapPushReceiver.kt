package app.clearsms.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Telephony
import android.util.Log
import app.clearsms.di.ApplicationScope
import app.clearsms.mms.MmsInbound
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Handles `WAP_PUSH_DELIVER` (incoming MMS notification for the default
 * app): the carrier's `m-notification-ind` PDU is parsed, a PENDING message
 * row is stored, and the content download is started (see [MmsInbound]).
 *
 * PRIVACY NOTE: retrieving the MMS content is the app's single deliberate
 * network interaction, and it is performed by the Android platform's MMS
 * service against the carrier's MMSC over the carrier network - that
 * transaction IS the MMS protocol; nothing is sent to any third party and
 * the app itself holds no INTERNET permission.
 *
 * A PDU that fails to parse is dropped (logged content-free): a hostile or
 * corrupt push must never crash the default SMS app.
 */
@AndroidEntryPoint
class MmsWapPushReceiver : BroadcastReceiver() {
    @Inject
    lateinit var mmsInbound: MmsInbound

    @Inject
    @ApplicationScope
    lateinit var applicationScope: CoroutineScope

    override fun onReceive(
        context: Context,
        intent: Intent,
    ) {
        if (intent.action != Telephony.Sms.Intents.WAP_PUSH_DELIVER_ACTION) return
        // The pushed m-notification-ind bytes ride the standard "data" extra.
        val pdu = intent.getByteArrayExtra("data") ?: return
        val pendingResult = goAsync()
        applicationScope.launch {
            try {
                if (mmsInbound.onNotification(pdu) == null) {
                    Log.w(TAG, "Undecodable MMS notification; dropping")
                }
            } catch (e: Exception) {
                // A storage or download-start failure must never crash the
                // process - the default SMS app has to survive every
                // incoming broadcast. Content-free by convention.
                Log.e(TAG, "Failed to handle incoming MMS notification", e)
            } finally {
                pendingResult.finish()
            }
        }
    }

    private companion object {
        const val TAG = "MmsWapPushReceiver"
    }
}
