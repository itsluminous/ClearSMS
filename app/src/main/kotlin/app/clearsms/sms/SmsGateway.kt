package app.clearsms.sms

import android.app.PendingIntent
import android.content.Context
import android.os.Build
import android.telephony.SmsManager
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Thin seam over the framework [SmsManager] calls [SmsSender] makes, so
 * unit tests can fake the radio instead of shadowing it. Custom
 * `@Config(shadows = ...)` values give each test class its own Robolectric
 * sandbox classloader, and multiple sandboxes race on the JVM-global
 * native-runtime extraction (see RobolectricSandboxConventionTest) - a
 * plain interface avoids the shadow entirely.
 *
 * Implementations must be pure delegation: no behaviour of their own.
 */
interface SmsGateway {
    /**
     * [SmsManager.divideMessage] on the manager for [subscriptionId]
     * (null = the system default manager).
     */
    fun divideMessage(
        subscriptionId: Int?,
        body: String,
    ): ArrayList<String>

    /**
     * [SmsManager.sendMultipartTextMessage] on the manager for
     * [subscriptionId] (null = the system default manager), with a null
     * service-centre address.
     */
    fun sendMultipartTextMessage(
        subscriptionId: Int?,
        destination: String,
        parts: ArrayList<String>,
        sentIntents: ArrayList<PendingIntent>,
        deliveryIntents: ArrayList<PendingIntent?>,
    )
}

/** Production [SmsGateway]: delegates straight to the framework [SmsManager]. */
@Singleton
class FrameworkSmsGateway
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
    ) : SmsGateway {
        override fun divideMessage(
            subscriptionId: Int?,
            body: String,
        ): ArrayList<String> = smsManagerFor(subscriptionId).divideMessage(body)

        override fun sendMultipartTextMessage(
            subscriptionId: Int?,
            destination: String,
            parts: ArrayList<String>,
            sentIntents: ArrayList<PendingIntent>,
            deliveryIntents: ArrayList<PendingIntent?>,
        ) {
            smsManagerFor(subscriptionId).sendMultipartTextMessage(
                destination,
                null,
                parts,
                sentIntents,
                deliveryIntents,
            )
        }

        /**
         * The [SmsManager] for the chosen SIM. With no choice (null) the
         * system-default manager is used - the pre-dual-SIM behaviour. Per
         * API level: `createForSubscriptionId` on S+, the static
         * `getSmsManagerForSubscriptionId` before it.
         */
        private fun smsManagerFor(subscriptionId: Int?): SmsManager =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val default = requireNotNull(context.getSystemService(SmsManager::class.java))
                if (subscriptionId != null) default.createForSubscriptionId(subscriptionId) else default
            } else {
                @Suppress("DEPRECATION")
                if (subscriptionId != null) {
                    SmsManager.getSmsManagerForSubscriptionId(subscriptionId)
                } else {
                    SmsManager.getDefault()
                }
            }
    }
