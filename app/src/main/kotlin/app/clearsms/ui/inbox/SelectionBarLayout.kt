package app.clearsms.ui.inbox

/** Actions reachable from the inbox multi-select bar. */
enum class SelectionAction {
    TOGGLE_READ,
    ARCHIVE,
    DELETE,
    PIN,
    UNPIN,
    SELECT_ALL,
    BLOCK,
    CHANGE_CATEGORY,
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

    /**
     * Overflow menu entries in display order. Block / change-category act on
     * one sender, so they appear only when exactly one thread is selected.
     */
    fun overflowActions(
        allSelectedPinned: Boolean,
        singleThread: Boolean,
    ): List<SelectionAction> =
        buildList {
            add(pinAction(allSelectedPinned))
            add(SelectionAction.SELECT_ALL)
            if (singleThread) {
                add(SelectionAction.BLOCK)
                add(SelectionAction.CHANGE_CATEGORY)
            }
        }
}
