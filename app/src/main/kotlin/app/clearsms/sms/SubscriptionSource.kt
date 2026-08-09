package app.clearsms.sms

/** One active SIM subscription, reduced to what the send UI needs. */
data class SimInfo(
    val subscriptionId: Int,
    /** Physical slot, 0-based; shown to the user as "SIM ${slotIndex + 1}". */
    val slotIndex: Int,
    /** Operator / user-given name for the subscription ("Airtel", "Work"). */
    val displayName: String,
)

/**
 * Seam over the platform's subscription APIs so ViewModels never touch
 * [android.telephony.SubscriptionManager] directly - selector and memory
 * logic stay unit-testable with fake subscription lists, and the production
 * implementation can degrade honestly (no permission → no SIMs → the
 * dual-SIM UI simply never appears).
 */
interface SubscriptionSource {
    /** Active subscriptions, empty when unavailable (no permission, no telephony). */
    fun activeSims(): List<SimInfo>

    /** The system default SMS subscription id, or null when none is set. */
    fun defaultSmsSubscriptionId(): Int?
}

/**
 * Pure SIM-choice rules for outgoing messages. The fallback chain is, in
 * order: the user's remembered per-recipient choice, the SIM of the last
 * message exchanged in the thread, the system default SMS subscription, the
 * first active SIM. Every candidate must still be ACTIVE - a remembered SIM
 * that was removed falls through to the next rung instead of silently
 * failing the send.
 */
object SimSelector {
    /** The subscription to send with, or null when no SIM is active. */
    fun choose(
        activeSims: List<SimInfo>,
        remembered: Int?,
        lastUsedInThread: Int?,
        defaultSubscriptionId: Int?,
    ): Int? {
        val active = activeSims.map { it.subscriptionId }.toSet()
        return sequenceOf(remembered, lastUsedInThread, defaultSubscriptionId)
            .filterNotNull()
            .firstOrNull { it in active }
            ?: activeSims.firstOrNull()?.subscriptionId
    }

    /** The next SIM in slot order after [current] (tap-to-cycle), wrapping. */
    fun next(
        activeSims: List<SimInfo>,
        current: Int?,
    ): Int? {
        if (activeSims.isEmpty()) return null
        val ordered = activeSims.sortedBy { it.slotIndex }
        val index = ordered.indexOfFirst { it.subscriptionId == current }
        return ordered[(index + 1) % ordered.size].subscriptionId
    }

    /** The compact compose-bar indicator exists only on true dual-SIM devices. */
    fun indicatorVisible(activeSims: List<SimInfo>): Boolean = activeSims.size >= 2

    /**
     * Whether bubbles should carry a "SIM n" tag: the device currently has
     * 2+ SIMs, or the stored corpus spans 2+ subscriptions (messages from a
     * SIM that has since been removed still deserve their provenance).
     */
    fun showSimTags(
        activeSims: List<SimInfo>,
        corpusSubscriptionIds: Collection<Int>,
    ): Boolean = activeSims.size >= 2 || corpusSubscriptionIds.distinct().size >= 2

    /** "SIM 1"/"SIM 2" for an active subscription; null when unknown. */
    fun slotLabelFor(
        activeSims: List<SimInfo>,
        subscriptionId: Int?,
    ): String? =
        activeSims
            .firstOrNull { it.subscriptionId == subscriptionId }
            ?.let { "SIM ${it.slotIndex + 1}" }

    /** 1-based slot number for an active subscription; null when unknown. */
    fun slotNumberFor(
        activeSims: List<SimInfo>,
        subscriptionId: Int?,
    ): Int? =
        activeSims
            .firstOrNull { it.subscriptionId == subscriptionId }
            ?.let { it.slotIndex + 1 }
}
