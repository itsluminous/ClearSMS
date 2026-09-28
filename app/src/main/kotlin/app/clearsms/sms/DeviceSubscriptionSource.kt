package app.clearsms.sms

import android.content.Context
import android.os.Build
import android.telephony.SubscriptionManager
import androidx.annotation.ChecksSdkIntAtLeast
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * [SubscriptionSource] backed by the platform [SubscriptionManager].
 *
 * `activeSubscriptionInfoList` requires READ_PHONE_STATE - already part of
 * the app's onboarding permission set. If the user declined it (or telephony
 * is absent) this returns an EMPTY list instead of throwing: the dual-SIM UI
 * then never appears and sends fall through to the system-default
 * [android.telephony.SmsManager], exactly the pre-feature behaviour.
 *
 * The two system defaults (SMS and DATA) are queried through static
 * [SubscriptionManager] calls that exist from API 24 and need no
 * permission; below that there is no public query and both read as
 * unknown. [sdkInt] is injectable (defaulting to the device's real level)
 * so the pre-24 branch is unit-testable in the default Robolectric sandbox
 * - a `@Config(sdk = ...)` pin would split the sandbox (see
 * RobolectricSandboxConventionTest).
 */
@Singleton
class DeviceSubscriptionSource(
    private val context: Context,
    private val sdkInt: Int,
) : SubscriptionSource {
    @Inject
    constructor(
        @ApplicationContext context: Context,
    ) : this(context, Build.VERSION.SDK_INT)

    override fun activeSims(): List<SimInfo> {
        val manager =
            context.getSystemService(SubscriptionManager::class.java) ?: return emptyList()
        val infos =
            try {
                manager.activeSubscriptionInfoList
            } catch (_: SecurityException) {
                null
            } ?: return emptyList()
        return infos.map { info ->
            SimInfo(
                subscriptionId = info.subscriptionId,
                slotIndex = info.simSlotIndex,
                displayName = info.displayName?.toString().orEmpty(),
                // The colour the user already sees in system settings.
                // Fully transparent means "none was assigned".
                iconTint = info.iconTint.takeIf { it ushr 24 != 0 },
            )
        }
    }

    override fun defaultSmsSubscriptionId(): Int? {
        // getDefaultSmsSubscriptionId exists from API 24; on 23 there is
        // no queryable default - callers fall back to the first SIM.
        if (!atLeast(sdkInt, Build.VERSION_CODES.N)) return null
        return validSubscription(SubscriptionManager.getDefaultSmsSubscriptionId())
    }

    override fun defaultDataSubscriptionId(): Int? {
        // Same API-24 floor as the SMS default. INVALID_SUBSCRIPTION_ID (no
        // data SIM chosen, or no telephony) reads as unknown, so nothing
        // downstream can mistake it for a real SIM.
        if (!atLeast(sdkInt, Build.VERSION_CODES.N)) return null
        return validSubscription(SubscriptionManager.getDefaultDataSubscriptionId())
    }

    companion object {
        /**
         * The `sdkInt >= level` check, shaped so lint's NewApi analysis
         * follows the injectable [sdkInt] the same way it follows
         * `Build.VERSION.SDK_INT`.
         */
        @ChecksSdkIntAtLeast(parameter = 1)
        private fun atLeast(
            sdkInt: Int,
            level: Int,
        ): Boolean = sdkInt >= level

        /** A platform subscription id as the app's nullable form: [SubscriptionManager.INVALID_SUBSCRIPTION_ID] is unknown. */
        internal fun validSubscription(raw: Int): Int? = raw.takeIf { it != SubscriptionManager.INVALID_SUBSCRIPTION_ID }
    }
}
