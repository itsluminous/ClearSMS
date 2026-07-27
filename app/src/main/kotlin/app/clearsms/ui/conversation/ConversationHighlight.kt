package app.clearsms.ui.conversation

/**
 * Resolves the list index to scroll to for a highlight [targetId] carried by
 * the navigation (search result tap or notification deep link). Returns null
 * when there is nothing to highlight — no target, or the target message is
 * not (or no longer) in the thread.
 */
fun highlightIndexFor(
    messageIds: List<Long>,
    targetId: Long?,
): Int? {
    if (targetId == null || targetId <= 0) return null
    val index = messageIds.indexOf(targetId)
    return if (index >= 0) index else null
}
