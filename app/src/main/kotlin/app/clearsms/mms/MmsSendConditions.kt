package app.clearsms.mms

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.telephony.TelephonyManager
import androidx.core.content.ContextCompat
import app.clearsms.sms.SimSelector
import app.clearsms.sms.SubscriptionSource
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * What the radio looked like at the instant an MMS was handed to the
 * platform - the half of an MMS bug report the result code cannot carry.
 * Every value is a yes/no or a slot number: nothing here names a carrier,
 * a network or a subscription id, and each is null when the platform would
 * not say (no telephony, permission declined, older API).
 */
data class MmsSendConditions(
    /** Some network was up (any transport). */
    val networkConnected: Boolean?,
    /** That network was the mobile one - MMS can only ride cellular. */
    val cellular: Boolean?,
    /** Mobile data is switched on for the sending subscription. */
    val mobileDataEnabled: Boolean?,
    /** 1-based SIM slot the subscription sits in; null when unknown or system default. */
    val slot: Int?,
)

/** Reads [MmsSendConditions] from the platform; failures read as "unknown", never throw. */
@Singleton
class MmsSendConditionsProbe
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
        private val subscriptionSource: SubscriptionSource,
    ) {
        fun probe(subscriptionId: Int?): MmsSendConditions {
            val network = network()
            return MmsSendConditions(
                networkConnected = network?.first,
                cellular = network?.second,
                mobileDataEnabled = mobileDataEnabled(subscriptionId),
                slot = runCatching { SimSelector.slotNumberFor(subscriptionSource.activeSims(), subscriptionId) }.getOrNull(),
            )
        }

        private fun network(): Pair<Boolean, Boolean>? =
            try {
                val cm = context.getSystemService(ConnectivityManager::class.java) ?: return null
                val caps = cm.activeNetwork?.let(cm::getNetworkCapabilities) ?: return false to false
                true to caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)
            } catch (_: Exception) {
                null
            }

        /**
         * `isDataEnabled` wants READ_PHONE_STATE (or MODIFY_PHONE_STATE);
         * the app asks for the former at onboarding but the user may have
         * declined, so the check is explicit and a denial reads as unknown.
         */
        private fun mobileDataEnabled(subscriptionId: Int?): Boolean? {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return null
            val granted =
                ContextCompat.checkSelfPermission(context, Manifest.permission.READ_PHONE_STATE) ==
                    PackageManager.PERMISSION_GRANTED
            if (!granted) return null
            return try {
                val tm = context.getSystemService(TelephonyManager::class.java) ?: return null
                (if (subscriptionId != null) tm.createForSubscriptionId(subscriptionId) else tm).isDataEnabled
            } catch (_: SecurityException) {
                null
            } catch (_: Exception) {
                null
            }
        }
    }
