package app.clearsms.ui.conversation

/**
 * Resolves the list index to scroll to for a highlight [targetId] carried by
 * the navigation (search result tap or notification deep link). Returns null
 * when there is nothing to highlight - no target, or the target message is
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

/** The nav argument's raw value mapped to a usable target (-1 default = none). */
fun highlightTargetOf(rawMessageIdArg: Long?): Long? = rawMessageIdArg?.takeIf { it > 0 }

/** Ordering key of one thread message, for [newestFirstPositionOf]. */
data class MessageOrderKey(
    val id: Long,
    val timestamp: Long,
)

/**
 * Index of [targetId] under the conversation pager's newest-first ordering,
 * `ORDER BY timestamp DESC, id DESC` - the in-memory mirror of
 * `MessageDao.newerCountInThread`, kept pure so tests can pin the
 * tie-breaking: a same-timestamp row with a HIGHER id sorts before the
 * target and must be counted, or the position is off and the wrong row
 * would flash. Null when the target is not in the thread.
 */
fun newestFirstPositionOf(
    keys: List<MessageOrderKey>,
    targetId: Long,
): Int? {
    val target = keys.firstOrNull { it.id == targetId } ?: return null
    return keys.count {
        it.timestamp > target.timestamp || (it.timestamp == target.timestamp && it.id > targetId)
    }
}

/**
 * The Pager `initialKey` for a resolved highlight position: the position
 * itself when the target needs a jump, null when it is already the newest
 * row (or there is no target), so the plain open-a-conversation path keeps
 * starting at the newest message.
 */
fun initialPagingKeyFor(position: Int?): Int? = position?.takeIf { it > 0 }

/**
 * State machine driving the scroll-to-and-highlight of the opened message.
 *
 * The paged conversation loads asynchronously, so the target message may
 * only appear in the loaded window after one or more page loads:
 * [onItemsLoaded] is fed every change of the loaded snapshot and stays
 * PENDING until the target shows up (never consuming the highlight early -
 * the previous implementation was a one-shot race against the first page).
 *
 * ```
 * IDLE                                (no target: nothing ever happens)
 * PENDING --target in snapshot--> SHOWN --fade finished--> DONE
 * ```
 */
class MessageHighlightState(
    private val targetId: Long?,
) {
    enum class Phase { IDLE, PENDING, SHOWN, DONE }

    var phase: Phase = if (targetId == null || targetId <= 0) Phase.IDLE else Phase.PENDING
        private set

    /**
     * Feed the currently loaded message ids. Returns the index to scroll to
     * exactly once - on the load where the target first became visible.
     */
    fun onItemsLoaded(messageIds: List<Long>): Int? {
        if (phase != Phase.PENDING) return null
        val index = highlightIndexFor(messageIds, targetId) ?: return null
        phase = Phase.SHOWN
        return index
    }

    /** True while the row with [id] should render the highlight background. */
    fun isHighlighted(id: Long): Boolean = phase == Phase.SHOWN && id == targetId

    /** The fade completed; the highlight never re-triggers afterwards. */
    fun onHighlightFinished() {
        if (phase == Phase.SHOWN) phase = Phase.DONE
    }
}
