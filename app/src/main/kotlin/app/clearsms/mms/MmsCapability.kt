package app.clearsms.mms

import android.content.Context
import android.os.Build
import android.telephony.SmsManager
import app.clearsms.diagnostics.Diag
import app.clearsms.diagnostics.DiagField.Companion.flag
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Whether the platform says MMS is usable at all on a given SIM.
 *
 * Asked for in issue #94, by a user whose carrier (O2 Germany) shut MMS
 * down entirely in July 2026: with no way to send or receive, the compose
 * bar's attach button is a button that can only ever fail.
 *
 * The one trustworthy signal Android offers is the carrier MMS config -
 * [SmsManager.getCarrierConfigValues] keyed by
 * [SmsManager.MMS_CONFIG_MMS_ENABLED], "whether MMS is enabled for the
 * current carrier". Honest limits, because they decide how this is used:
 *
 * - It reflects the carrier CONFIG table (keyed by MCC/MNC, or supplied by
 *   a carrier config app), not live network reachability. A carrier that
 *   switches the service off without updating that config still reads as
 *   enabled, so this cannot be the only answer for every such user.
 * - It is therefore treated as a VETO, never as a requirement: the button
 *   is hidden only when the platform explicitly says `false`. Unknown -
 *   no telephony, no SIM, a config the platform will not hand over -
 *   leaves MMS available, because wrongly hiding the button on a working
 *   SIM is far worse than leaving a button that reports a clear failure.
 *
 * Device locale and time zone are deliberately NOT consulted. A German
 * phone can hold a working foreign SIM, a traveller's locale says nothing
 * about their carrier, and a dual-SIM phone can have one of each - so the
 * question is only ever asked of the SIM that would actually send.
 */
@Singleton
class MmsCapability
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
    ) {
        /**
         * False only when the carrier config explicitly disables MMS for
         * [subscriptionId] (null = the phone's default SMS subscription).
         * True whenever MMS is enabled or the platform will not say.
         */
        fun isMmsAvailable(subscriptionId: Int?): Boolean = explicitlyDisabled(subscriptionId) != true

        /**
         * The raw reading: true when the config says MMS is off, false when
         * it says it is on, null when the platform would not answer.
         *
         * Every branch logs. An un-diagnosable "the button is missing" (or
         * "the button is there and sends keep failing") report is the whole
         * reason this class exists, so a silent path here would defeat it.
         */
        private fun explicitlyDisabled(subscriptionId: Int?): Boolean? =
            try {
                val manager = smsManager(subscriptionId)
                if (manager == null) {
                    Diag.d(TAG, "no sms manager for the sending sim", flag("subscriptionGiven", subscriptionId != null))
                    return null
                }
                val config = manager.carrierConfigValues
                if (config == null) {
                    Diag.d(TAG, "carrier mms config absent")
                    return null
                }
                if (!config.containsKey(SmsManager.MMS_CONFIG_MMS_ENABLED)) {
                    // Common and harmless: many configs simply omit the key,
                    // which is not a statement that MMS is unavailable.
                    Diag.d(TAG, "carrier mms config omits the enabled key")
                    return null
                }
                val enabled = config.getBoolean(SmsManager.MMS_CONFIG_MMS_ENABLED, true)
                Diag.d(TAG, "carrier mms config read", flag("mmsEnabled", enabled))
                !enabled
            } catch (e: Exception) {
                // No telephony, no SIM, an OEM that throws: unknown.
                Diag.d(TAG, "carrier mms config unavailable", flag("error", true))
                null
            }

        /**
         * The [SmsManager] whose carrier config to read, for the SIM that
         * would send. Per API level exactly as [app.clearsms.sms.SmsGateway]
         * does it: `createForSubscriptionId` on S+, the static
         * `getSmsManagerForSubscriptionId` before it (the instance method
         * only exists from API 31).
         */
        private fun smsManager(subscriptionId: Int?): SmsManager? =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val default = context.getSystemService(SmsManager::class.java) ?: return null
                if (subscriptionId != null && subscriptionId >= 0) {
                    default.createForSubscriptionId(subscriptionId)
                } else {
                    default
                }
            } else {
                @Suppress("DEPRECATION")
                if (subscriptionId != null && subscriptionId >= 0) {
                    SmsManager.getSmsManagerForSubscriptionId(subscriptionId)
                } else {
                    SmsManager.getDefault()
                }
            }

        private companion object {
            const val TAG = "MmsCapability"
        }
    }
