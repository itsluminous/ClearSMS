package app.clearsms.mms

import app.clearsms.sms.SimInfo
import app.clearsms.sms.SimSelector

/**
 * The one MMS fact the send result cannot carry: whether the message went
 * out on the phone's mobile-data SIM. On most dual-SIM Androids only the
 * default DATA subscription can bring up the MMS bearer, so an MMS sent
 * from the other SIM fails with "no MMS connection" on Wi-Fi and on
 * cellular alike - indistinguishable, from the result code, from a
 * carrier that has switched MMS off. Pure, so the log fields and the
 * user hint are unit-testable with synthetic subscription lists.
 *
 * Every output is a slot number or a yes/no: nothing here names a
 * carrier, a number or an ICCID.
 */
object DataSim {
    /**
     * The failure reasons where "not the data SIM" is a plausible cause:
     * [SendFailureReason.NO_MMS_NETWORK] (the bearer never came up - exactly
     * what a non-data SIM produces) and [SendFailureReason.APN_CONFIGURATION]
     * (the platform resolves MMS APN settings per subscription, and on
     * several OEMs the non-data SIM's lookup fails as "no APN"). NOT
     * [SendFailureReason.HTTP_FAILURE] - the MMSC answered, so the bearer
     * was up and the SIM is not the problem - and not the transient,
     * carrier-disabled, SIM-gone, our-side or unknown reasons, where the
     * hint would be noise.
     */
    val HINTED_REASONS: Set<SendFailureReason> =
        setOf(SendFailureReason.NO_MMS_NETWORK, SendFailureReason.APN_CONFIGURATION)

    /**
     * Whether [sendingSubscriptionId] is the default data subscription:
     * true/false when both are known, null when either is not (system
     * default sender, pre-API-24, no data SIM chosen).
     */
    fun sendsOnDataSim(
        sendingSubscriptionId: Int?,
        dataSubscriptionId: Int?,
    ): Boolean? =
        if (sendingSubscriptionId == null || dataSubscriptionId == null) {
            null
        } else {
            sendingSubscriptionId == dataSubscriptionId
        }

    /**
     * The user-facing hint for a failed MMS, or null when it does not
     * apply. It applies only when ALL of these hold: the [reason] is one of
     * [HINTED_REASONS]; the device currently has two or more SIMs; both the
     * sending and the default data subscription are known AND active; and
     * they differ. A single-SIM device, an unknown data SIM, a removed
     * sending SIM or matching SIMs all say nothing extra - the hint is
     * guidance about a LIKELY cause, and it must never fire where it
     * cannot be one.
     */
    fun hintFor(
        reason: SendFailureReason?,
        sendingSubscriptionId: Int?,
        dataSubscriptionId: Int?,
        activeSims: List<SimInfo>,
    ): DataSimHint? {
        if (reason !in HINTED_REASONS) return null
        if (activeSims.size < 2) return null
        if (sendingSubscriptionId == null || dataSubscriptionId == null) return null
        if (sendingSubscriptionId == dataSubscriptionId) return null
        val sendingSlot = SimSelector.slotNumberFor(activeSims, sendingSubscriptionId) ?: return null
        val dataSlot = SimSelector.slotNumberFor(activeSims, dataSubscriptionId) ?: return null
        return DataSimHint(sendingSlot = sendingSlot, dataSlot = dataSlot)
    }
}

/** "Sent from SIM [sendingSlot]; SIM [dataSlot] is the mobile-data SIM" - 1-based slots, nothing else. */
data class DataSimHint(
    val sendingSlot: Int,
    val dataSlot: Int,
)
