package app.clearsms.ui.conversation

/** Actions reachable from the conversation multi-select bar. */
enum class MessageSelectionAction {
    COPY,
    DELETE,
    FORWARD,
    SHARE,
    SELECT_ALL,
    COPY_OTP,
    ADD_RULE,
}

/**
 * Pure layout rules for the conversation selection bar - the same
 * 3-inline-plus-overflow contract as the inbox bar
 * ([app.clearsms.ui.inbox.SelectionBarLayout]): at most THREE inline icon
 * actions so a six-digit "999999 selected" title never wraps out of view
 * on a 411dp-wide display.
 *
 * The inline trio is chosen by frequency of use: copy and delete are the
 * bread-and-butter message actions, and forward keeps the text inside the
 * user's main SMS workflow (send it to someone else), so it earns the third
 * slot. Share hands text to OTHER apps - a rarer hop - and select-all is
 * occasional, so both live in the overflow together with the
 * single-selection extras (copy OTP, add rule).
 */
object ConversationSelectionBarLayout {
    /** The fixed inline icon actions, most-used first. Never more than three. */
    val inlineActions: List<MessageSelectionAction> =
        listOf(
            MessageSelectionAction.COPY,
            MessageSelectionAction.DELETE,
            MessageSelectionAction.FORWARD,
        )

    /**
     * Overflow menu entries in display order. The overflow always exists
     * (share and select-all apply to any selection); copy-OTP and add-rule
     * act on one message, so they appear only for a single selection.
     */
    fun overflowActions(
        singleMessage: Boolean,
        hasOtp: Boolean,
    ): List<MessageSelectionAction> =
        buildList {
            add(MessageSelectionAction.SHARE)
            add(MessageSelectionAction.SELECT_ALL)
            if (singleMessage && hasOtp) add(MessageSelectionAction.COPY_OTP)
            if (singleMessage) add(MessageSelectionAction.ADD_RULE)
        }
}
