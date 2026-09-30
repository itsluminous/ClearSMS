package app.clearsms.sms

import android.content.Context
import android.os.Build
import android.telephony.SubscriptionManager
import android.telephony.TelephonyManager
import app.clearsms.data.repository.SenderNormalizer
import app.clearsms.diagnostics.Diag
import app.clearsms.diagnostics.DiagField.Companion.flag

/**
 * Where the region behind [SenderNormalizer.defaultRegion] comes from: the
 * SIM the device sends SMS with, then any SIM, then the network the device
 * is registered on. None of these reads needs a runtime permission. No
 * region at all (no SIM, no service) leaves the normalizer in its
 * conservative mode - it never guesses a country.
 *
 * Installed once per process before the database opens (the v21→v22
 * thread re-key runs under the same region every later insert will use)
 * and refreshed at application start.
 */
object SenderRegion {
    fun install(context: Context) {
        val region = detect(context)
        SenderNormalizer.defaultRegion = region
        Diag.i(TAG, "sender region", flag("known", region != null))
    }

    /** ISO 3166-1 alpha-2, upper case, or null when the device cannot say. */
    fun detect(context: Context): String? {
        val telephony = context.getSystemService(TelephonyManager::class.java) ?: return null
        return try {
            val forSmsSim =
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                    SubscriptionManager
                        .getDefaultSmsSubscriptionId()
                        .takeIf { it != SubscriptionManager.INVALID_SUBSCRIPTION_ID }
                        ?.let { telephony.createForSubscriptionId(it).simCountryIso }
                } else {
                    null
                }
            sequenceOf(forSmsSim, telephony.simCountryIso, telephony.networkCountryIso)
                .firstOrNull { !it.isNullOrBlank() }
                ?.uppercase()
                ?.takeIf { it.length == 2 }
        } catch (e: Exception) {
            Diag.w(TAG, "cannot read the SIM region", e)
            null
        }
    }

    private const val TAG = "SenderRegion"
}
