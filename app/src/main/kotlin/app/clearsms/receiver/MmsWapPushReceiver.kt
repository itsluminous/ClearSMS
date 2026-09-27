package app.clearsms.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Telephony
import app.clearsms.di.ApplicationScope
import app.clearsms.diagnostics.Diag
import app.clearsms.diagnostics.DiagField.Companion.count
import app.clearsms.diagnostics.DiagField.Companion.flag
import app.clearsms.diagnostics.DiagField.Companion.id
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
        val pdu = intent.getByteArrayExtra("data")
        if (pdu == null) {
            // A WAP push with no payload is a platform oddity, not ours -
            // but a "my MMS never arrive" report needs to see it happened.
            Diag.w(TAG, "wap push without pdu", null, flag("hasSubscription", intent.hasExtra("subscription")))
            return
        }
        val pendingResult = goAsync()
        applicationScope.launch {
            try {
                val stored = mmsInbound.onNotification(pdu)
                if (stored == null) {
                    Diag.w(TAG, "undecodable mms notification dropped", null, count("pduBytes", pdu.size))
                } else {
                    Diag.i(TAG, "mms notification accepted", id("message", stored), count("pduBytes", pdu.size))
                }
            } catch (e: Exception) {
                // A storage or download-start failure must never crash the
                // process - the default SMS app has to survive every
                // incoming broadcast. Content-free by convention.
                Diag.e(TAG, "incoming mms notification failed", e, count("pduBytes", pdu.size))
            } finally {
                pendingResult.finish()
            }
        }
    }

    private companion object {
        const val TAG = "MmsWapPushReceiver"
    }
}
