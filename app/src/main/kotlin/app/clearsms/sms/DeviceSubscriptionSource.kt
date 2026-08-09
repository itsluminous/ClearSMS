package app.clearsms.sms

import android.content.Context
import android.os.Build
import android.telephony.SubscriptionManager
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
 */
@Singleton
class DeviceSubscriptionSource
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
    ) : SubscriptionSource {
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
                )
            }
        }

        override fun defaultSmsSubscriptionId(): Int? {
            // getDefaultSmsSubscriptionId exists from API 24; on 23 there is
            // no queryable default - callers fall back to the first SIM.
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return null
            return SubscriptionManager
                .getDefaultSmsSubscriptionId()
                .takeIf { it != SubscriptionManager.INVALID_SUBSCRIPTION_ID }
        }
    }
