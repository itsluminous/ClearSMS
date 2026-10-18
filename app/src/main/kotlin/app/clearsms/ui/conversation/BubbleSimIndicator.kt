package app.clearsms.ui.conversation

import app.clearsms.sms.SimInfo
import app.clearsms.sms.SimSelector

/**
 * The at-a-glance SIM mark beside a bubble's time label (GitHub #109),
 * following the delivery-tick shape ([DeliveryTicks]): a pure decision
 * from persisted state plus the device's current SIM list, with ONE place
 * that renders it. Reporters had been long-pressing into "More details"
 * just to learn which SIM a message belongs to; this puts the same fact on
 * the bubble, without adding any new information.
 *
 * The result IS the details dialog's SIM row ([MessageDetails.Row.Sim]),
 * resolved by [MessageDetails.simRowFor] - the bubble never re-derives a
 * slot, so the mark and the dialog can never disagree about which SIM a
 * message belongs to. On top of that shared rule, exactly one extra gate:
 *
 * - **Dual-SIM devices only** ([SimSelector.indicatorVisible], the same
 *   gate the compose bar's SIM indicator uses). On a single-SIM phone a
 *   SIM mark says nothing, so the bubble stays exactly as it was - no icon,
 *   no spacer. The gate is the live SIM list, not the stored corpus: a
 *   phone that has gone from two SIMs to one has nothing to disambiguate.
 *
 * The shared rule then supplies the rest: a message with no recorded
 * [app.clearsms.data.db.MessageEntity.subscriptionId] (imported, or from
 * before SIMs were recorded) shows nothing, per the maintainer's "if sim
 * info is not present for a message, then don't show"; and a stored
 * subscription that is no longer among the active SIMs (removed or
 * swapped) shows nothing rather than a wrong slot - the platform mints a
 * fresh subscription id for every inserted SIM, so only an id that is on
 * the device NOW names a slot this phone can vouch for.
 *
 * Both directions. The maintainer wrote "which sim sent it", the reporters
 * asked "which SIM received it"; an incoming row's subscription id is the
 * SIM that received it, the details dialog already shows its SIM row for
 * incoming messages too, and with two SIMs on the same carrier (GitHub
 * #7) knowing which one a bank's OTP arrived on is the whole point. So
 * direction plays no part here - the rule is the dialog's rule, applied
 * to every bubble.
 */
object BubbleSimIndicators {
    /**
     * The SIM to mark a bubble with, or null for no mark: not a dual-SIM
     * device, no recorded subscription, or a subscription no longer present.
     */
    fun forMessage(
        activeSims: List<SimInfo>,
        subscriptionId: Int?,
    ): MessageDetails.Row.Sim? {
        if (!SimSelector.indicatorVisible(activeSims)) return null
        return MessageDetails.simRowFor(activeSims, subscriptionId)
    }

    /** The bubble's view: the backing row's subscription id, none when the item carries no row. */
    fun forItem(
        item: ConversationItem,
        activeSims: List<SimInfo>,
    ): MessageDetails.Row.Sim? = forMessage(activeSims, item.message?.subscriptionId)
}
