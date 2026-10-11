package app.clearsms.notification

import app.clearsms.domain.model.NotificationAction
import app.clearsms.sms.SenderRepliability
import app.clearsms.ui.components.DialableNumber

/**
 * Pure logic that turns the user's [NotificationAction] selection into the
 * ordered action list of a concrete notification.
 *
 * Ordering: the selection is persisted as an unordered set, so "the user's
 * selected order" is defined as the [NotificationAction] declaration order
 * restricted to the selected entries - deterministic across processes.
 *
 * The platform renders at most three actions ([MAX_ACTIONS]); anything
 * beyond that is dropped from the end. The Settings picker already refuses
 * a fourth selection, so this cap only bites on a hand-edited or restored
 * selection.
 */
object NotificationActionPlanner {
    /** Android ignores actions beyond the first three. */
    const val MAX_ACTIONS = 3

    /**
     * Actions for a plain message or transaction notification. OTP-only
     * actions never apply here. REPLY is offered only when [repliable] -
     * see [isRepliableAddress]; CALL only when [dialable] - see
     * [dialableAddress]. An ineligible sender gets the button REMOVED, never
     * a button that cannot work (the #78 class of bug).
     */
    fun forMessage(
        selected: Set<NotificationAction>,
        repliable: Boolean,
        dialable: Boolean,
    ): List<NotificationAction> =
        NotificationAction.entries
            .filter { it in selected }
            .filter {
                when (it) {
                    NotificationAction.MARK_READ, NotificationAction.DELETE, NotificationAction.SHARE -> true
                    NotificationAction.REPLY -> repliable
                    NotificationAction.CALL -> dialable
                    NotificationAction.COPY_OTP, NotificationAction.SHARE_OTP -> false
                }
            }.take(MAX_ACTIONS)

    /**
     * Actions for an OTP notification. Copy is ALWAYS first and always
     * available regardless of the selection (it is the whole point of the
     * notification); the rest honor the user's selection. REPLY is skipped -
     * an OTP is something to copy, not to answer, and the three slots are
     * better spent (whether the short code behind it would accept a reply is
     * beside the point here). CALL is skipped for the same reason: an OTP
     * comes from a service, not a person one would phone back, and nearly
     * every OTP sender is a short code or alphanumeric id [DialableNumber]
     * would refuse anyway. SHARE is skipped too: OTP
     * notifications already have the dedicated SHARE_OTP action, and two
     * share buttons on one notification would be confusing.
     */
    fun forOtp(selected: Set<NotificationAction>): List<NotificationAction> =
        (
            listOf(NotificationAction.COPY_OTP) +
                NotificationAction.entries.filter {
                    it in selected &&
                        it != NotificationAction.COPY_OTP &&
                        it != NotificationAction.REPLY &&
                        it != NotificationAction.CALL &&
                        it != NotificationAction.SHARE
                }
        ).take(MAX_ACTIONS)

    /**
     * A sender is repliable when the phone can address a reply to it: a
     * subscriber number OR a numeric short code (many are two-way - GitHub
     * #75). Alphanumeric ids are not, because the platform cannot encode a
     * name as an SMS destination. Delegates to the shared
     * [SenderRepliability] predicate so notifications and the conversation
     * composer agree on one rule.
     */
    fun isRepliableAddress(sender: String): Boolean = SenderRepliability.isAddressable(sender)

    /**
     * The number the CALL action would hand to the dialer, or null when the
     * sender is not one a person would dial - a short code ("139", "56767")
     * or an alphanumeric id ("VM-HDFCBK"). Dialability is a STRICTER rule
     * than repliability: a numeric short code can take an SMS reply but is
     * not a phone number. Delegates to the shared [DialableNumber] judgement
     * so the notification button and the conversation title bar's call
     * button can never disagree.
     */
    fun dialableAddress(sender: String): String? = DialableNumber.of(sender)
}
