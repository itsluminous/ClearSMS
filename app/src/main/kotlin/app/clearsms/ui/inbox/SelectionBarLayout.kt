package app.clearsms.ui.inbox

/** Actions reachable from the inbox multi-select bar. */
enum class SelectionAction {
    TOGGLE_READ,
    ARCHIVE,
    DELETE,
    PIN,
    UNPIN,
    SELECT_ALL,

    /**
     * Mute / unmute notifications for the one selected sender - a single
     * toggle whose entry reflects the current state (like PIN / UNPIN).
     * Messages keep arriving; only notifications stop
     * ([app.clearsms.data.repository.SenderMuter]).
     */
    MUTE,
    UNMUTE,
    BLOCK,

    /** "Always sort as…": the one-step sender rule ([app.clearsms.ui.rules.SenderRuleDialog]). */
    ALWAYS_SORT_AS,
}

/**
 * Pure layout rules for the inbox selection bar.
 *
 * The bar shows at most THREE inline icon actions plus a single overflow
 * menu: five inline icons used to push the "N selected" title out of view
 * once the count grew past a couple of digits. Three inline slots keep a
 * six-digit count ("999999 selected") fully visible on a 411dp-wide
 * display. The inline trio is chosen by frequency of use (mark-read,
 * archive, delete); pin/unpin, select-all and the single-thread actions
 * live in the overflow with proper labels.
 */
object SelectionBarLayout {
    /**
     * Whether the pin action would UNPIN: only when there IS a selection
     * and every selected thread is already pinned. A mixed selection keeps
     * "pin" - it pins the remaining unpinned threads (existing semantics).
     */
    fun isUnpin(
        selectedCount: Int,
        pinnedCount: Int,
    ): Boolean = selectedCount > 0 && pinnedCount == selectedCount

    /** The pin menu entry for the current selection: [SelectionAction.UNPIN] or [SelectionAction.PIN]. */
    fun pinAction(allSelectedPinned: Boolean): SelectionAction = if (allSelectedPinned) SelectionAction.UNPIN else SelectionAction.PIN

    /** The fixed inline icon actions, most-used first. Never more than three. */
    val inlineActions: List<SelectionAction> =
        listOf(SelectionAction.TOGGLE_READ, SelectionAction.ARCHIVE, SelectionAction.DELETE)

    /** The mute menu entry for the selected thread: [SelectionAction.UNMUTE] when it is already muted. */
    fun muteAction(singleThreadMuted: Boolean): SelectionAction = if (singleThreadMuted) SelectionAction.UNMUTE else SelectionAction.MUTE

    /**
     * Overflow menu entries in display order. Mute, Block and "Always sort
     * as…" act on ONE sender, so they appear only when exactly one thread is
     * selected; Mute precedes Block (the gentler action first, and the two
     * read as an escalation). Inbox threads are one per person (the platform
     * thread id, then the normalized sender key -
     * [app.clearsms.data.repository.ThreadIdentity]), so two selected threads are
     * two different senders and "always sort THIS sender as" has no
     * single answer: the entry is hidden (not disabled) for multi-select,
     * exactly as the conversation bar hides its single-message extras
     * ([app.clearsms.ui.conversation.ConversationSelectionBarLayout]).
     * [InboxScreen] renders the overflow FROM this list, so the menu cannot
     * drift from the rule.
     */
    fun overflowActions(
        allSelectedPinned: Boolean,
        singleThread: Boolean,
        singleThreadMuted: Boolean = false,
    ): List<SelectionAction> =
        buildList {
            add(pinAction(allSelectedPinned))
            add(SelectionAction.SELECT_ALL)
            if (singleThread) {
                add(muteAction(singleThreadMuted))
                add(SelectionAction.BLOCK)
                add(SelectionAction.ALWAYS_SORT_AS)
            }
        }
}
