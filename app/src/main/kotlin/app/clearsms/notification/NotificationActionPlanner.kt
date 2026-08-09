package app.clearsms.notification

import app.clearsms.domain.model.NotificationAction
import app.clearsms.sms.SenderRepliability

/**
 * Pure logic that turns the user's [NotificationAction] selection into the
 * ordered action list of a concrete notification.
 *
 * Ordering: the selection is persisted as an unordered set, so "the user's
 * selected order" is defined as the [NotificationAction] declaration order
 * restricted to the selected entries - deterministic across processes.
 *
 * The platform renders at most three actions ([MAX_ACTIONS]); anything
 * beyond that is dropped from the end.
 */
object NotificationActionPlanner {
    /** Android ignores actions beyond the first three. */
    const val MAX_ACTIONS = 3

    /**
     * Actions for a plain message or transaction notification. OTP-only
     * actions never apply here. REPLY is offered only when [repliable] -
     * see [isRepliableAddress].
     */
    fun forMessage(
        selected: Set<NotificationAction>,
        repliable: Boolean,
    ): List<NotificationAction> =
        NotificationAction.entries
            .filter { it in selected }
            .filter {
                when (it) {
                    NotificationAction.MARK_READ, NotificationAction.DELETE, NotificationAction.SHARE -> true
                    NotificationAction.REPLY -> repliable
                    NotificationAction.COPY_OTP, NotificationAction.SHARE_OTP -> false
                }
            }.take(MAX_ACTIONS)

    /**
     * Actions for an OTP notification. Copy is ALWAYS first and always
     * available regardless of the selection (it is the whole point of the
     * notification); the rest honor the user's selection. REPLY is skipped -
     * OTP senders are one-way short codes. SHARE is skipped too: OTP
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
                        it != NotificationAction.SHARE
                }
        ).take(MAX_ACTIONS)

    /**
     * A sender is repliable only when it looks like a real dialable phone
     * number. Delegates to the shared [SenderRepliability] core predicate so
     * notifications and the conversation composer agree on one rule.
     */
    fun isRepliableAddress(sender: String): Boolean = SenderRepliability.isDialableNumber(sender)
}
