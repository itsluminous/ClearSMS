package app.clearsms.ui.conversation

import app.clearsms.ui.navigation.PillConfig

/**
 * Actions reachable from the conversation multi-select bar. DECLARATION
 * ORDER IS THE DEFAULT ORDER (issue #61): the first
 * [ConversationSelectionBarLayout.INLINE_SLOTS] that apply to the current
 * selection sit in the bar, the rest go under "More options". Copy and
 * delete are the bread-and-butter message actions; "More details" earns the
 * third slot because the sent / received / delivered times are what people
 * open a selected message for, and Forward - which many SMS users never
 * touch - moves to the overflow where Share already lives. The user can
 * rearrange all of this from Settings > Messages > Message action order.
 *
 * The stored preference holds these NAMES, so renaming an entry is a
 * migration; adding one is not (it is appended in declaration order).
 */
enum class MessageSelectionAction {
    COPY,
    DELETE,
    MORE_DETAILS,
    FORWARD,
    SHARE,
    SELECT_ALL,
    COPY_OTP,
    ADD_RULE,
    ;

    /**
     * Whether this action applies to the current selection. Copy OTP, add
     * rule and more details act on ONE message - details of several
     * messages at once has no sensible rendering - so they exist only for a
     * single selection, and Copy OTP only when that message carries an OTP.
     * An action that does not apply is HIDDEN, not disabled, and the next
     * applicable action in the user's order takes its slot, so the bar
     * never shows a hole (see [ConversationSelectionBarLayout.resolve]).
     */
    fun appliesTo(
        singleMessage: Boolean,
        hasOtp: Boolean,
    ): Boolean =
        when (this) {
            COPY, DELETE, FORWARD, SHARE, SELECT_ALL -> true
            MORE_DETAILS, ADD_RULE -> singleMessage
            COPY_OTP -> singleMessage && hasOtp
        }
}

/** The bar's actions for one selection: what sits inline and what is under "More options". */
data class SelectionBarActions(
    val inline: List<MessageSelectionAction>,
    val overflow: List<MessageSelectionAction>,
)

/**
 * Pure layout rules for the conversation selection bar: the user's ORDER
 * of [MessageSelectionAction] (a preference, Settings > Messages) resolved
 * against the current selection into inline and overflow lists.
 *
 * - The order is resolved through the shared [PillConfig] - the same
 *   mechanism the Inbox, Finance and Alerts pill rows use (and the same
 *   [app.clearsms.ui.settings.PillOrderDialog] edits it), so a stale
 *   stored name is dropped and a newly added action is appended, never
 *   lost. ORDER ONLY, no hiding: every action stays reachable at worst
 *   one tap away in the overflow, so hiding would buy nothing and could
 *   trap a user (hide Delete and Select all and a bulk delete is gone).
 * - Actions that do not apply to the selection ([MessageSelectionAction.appliesTo])
 *   are removed BEFORE the split, so a single-message action placed first
 *   leaves no empty slot during a multi-select: the next applicable action
 *   moves up.
 * - [INLINE_SLOTS] is a LAYOUT constraint, not a preference: at most three
 *   inline icons (plus Close and More options) keep a six-digit "999999
 *   selected" title unwrapped on a 411dp-wide display - the same standard
 *   as the inbox bar ([app.clearsms.ui.inbox.SelectionBarLayout]). The
 *   count is capped by `take`, so however long the action list grows the
 *   bar cannot overflow on a narrow screen or at a large font scale.
 * - Five actions apply to EVERY selection and only three fit, so the
 *   overflow is never empty and the More button is always meaningful.
 *
 * The inbox selection bar is deliberately NOT covered: its action set
 * ([app.clearsms.ui.inbox.SelectionAction]) is a different vocabulary with
 * a different most-used trio, the request (issue #61) was about the
 * conversation bar, and one preference ordering two unrelated lists would
 * be meaningless. The inbox keeps its fixed layout.
 */
object ConversationSelectionBarLayout {
    /** How many icon actions fit beside the title. Never more than three. */
    const val INLINE_SLOTS = 3

    /** The out-of-the-box order (issue #61): Copy, Delete, More details inline. */
    val defaultOrder: List<MessageSelectionAction> = MessageSelectionAction.entries.toList()

    /**
     * Every action in the user's display order, resolved leniently through
     * [PillConfig]: unknown entries cannot be present (the repository
     * already decodes names), duplicates collapse and missing actions are
     * appended in declaration order. This is what the Settings dialog lists.
     */
    fun ordered(order: List<MessageSelectionAction>): List<MessageSelectionAction> =
        PillConfig(MessageSelectionAction.entries.toList(), order).ordered

    /**
     * The bar for one selection: the applicable actions in the user's
     * [order], the first [INLINE_SLOTS] inline and the rest in the
     * overflow. Nothing is ever dropped - every applicable action is in
     * exactly one of the two lists.
     */
    fun resolve(
        order: List<MessageSelectionAction>,
        singleMessage: Boolean,
        hasOtp: Boolean,
    ): SelectionBarActions {
        val applicable = ordered(order).filter { it.appliesTo(singleMessage, hasOtp) }
        return SelectionBarActions(
            inline = applicable.take(INLINE_SLOTS),
            overflow = applicable.drop(INLINE_SLOTS),
        )
    }

    /** The default bar for one selection - what a fresh install shows. */
    fun resolveDefault(
        singleMessage: Boolean,
        hasOtp: Boolean,
    ): SelectionBarActions = resolve(defaultOrder, singleMessage, hasOtp)
}
