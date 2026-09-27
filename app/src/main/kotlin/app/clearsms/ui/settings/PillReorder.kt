package app.clearsms.ui.settings

import kotlin.math.roundToInt

/**
 * Returns [order] with the item at [from] moved to [to]. Both indices are
 * CLAMPED into the list, never rejected: a drag that runs past the first or
 * last row lands on that row, and an out-of-range index from a stale
 * semantic action does the nearest sensible thing instead of throwing. An
 * empty list, or a move onto its own position, returns the list as-is.
 *
 * This is the one place a pill order is rearranged; the drag gesture and the
 * accessibility actions in [PillOrderDialog] are thin shells over it.
 */
fun <T> movedPill(
    order: List<T>,
    from: Int,
    to: Int,
): List<T> {
    if (order.size < 2) return order
    val source = from.coerceIn(order.indices)
    val target = to.coerceIn(order.indices)
    if (source == target) return order
    return order.toMutableList().apply { add(target, removeAt(source)) }
}

/**
 * The reorder dialog's state machine, kept free of Compose so it is unit
 * testable: the working [order], which row (if any) is being dragged and
 * how far its finger has travelled from the slot it currently occupies.
 *
 * The dragged row follows the finger ([offsetPx] is its visual
 * translation); once it has travelled more than half a row it swaps slots
 * with its neighbour and the offset is re-based, so releasing anywhere
 * settles deterministically on the slot the row is closest to. Nothing is
 * persisted while the finger moves - [finish] reports the new order exactly
 * once, and only if it actually changed.
 */
data class PillDragState<T>(
    val order: List<T>,
    /** Index of the row under the finger, or null while nothing is dragged. */
    val active: Int? = null,
    /** Visual translation of the active row from its current slot, in px. */
    val offsetPx: Float = 0f,
    /** The order when the drag began, to tell a real change from a wobble. */
    private val origin: List<T>? = null,
) {
    val isDragging: Boolean get() = active != null

    /** Starts dragging the row at [index]; out-of-range or empty is a no-op. */
    fun begin(index: Int): PillDragState<T> {
        if (index !in order.indices) return this
        return copy(active = index, offsetPx = 0f, origin = order)
    }

    /**
     * Moves the finger by [deltaPx]. Rows are [rowHeightPx] tall; until the
     * row has been measured (height <= 0) the offset accumulates but no swap
     * happens. The offset is clamped so the row can never leave the list.
     */
    fun dragBy(
        deltaPx: Float,
        rowHeightPx: Float,
    ): PillDragState<T> {
        val from = active ?: return this
        var offset = offsetPx + deltaPx
        var current = from
        var reordered = order
        if (rowHeightPx > 0f) {
            val shift = (offset / rowHeightPx).roundToInt()
            val target = (from + shift).coerceIn(order.indices)
            if (target != from) {
                reordered = movedPill(order, from, target)
                offset -= (target - from) * rowHeightPx
                current = target
            }
            offset = offset.coerceIn(-current * rowHeightPx, (order.lastIndex - current) * rowHeightPx)
        }
        return copy(order = reordered, active = current, offsetPx = offset)
    }

    /**
     * Ends the drag. [Settled.committed] is the new order when it differs
     * from the one the drag started with, else null - so the caller persists
     * at most once per completed drag and never for a drag that went nowhere.
     */
    fun finish(): Settled<T> {
        if (active == null) return Settled(this, committed = null)
        val changed = origin != null && origin != order
        return Settled(PillDragState(order), committed = order.takeIf { changed })
    }

    /** Abandons the drag, restoring the order it started from. */
    fun cancel(): PillDragState<T> = PillDragState(origin ?: order)

    /**
     * The accessible path (a screen reader's "Move up" / "Move down"
     * actions): moves the row at [index] by [delta] slots, clamped, and
     * commits immediately - a drag in progress is abandoned first.
     */
    fun moveBy(
        index: Int,
        delta: Int,
    ): Settled<T> {
        val base = if (isDragging) cancel().order else order
        val moved = movedPill(base, index, index + delta)
        return Settled(PillDragState(moved), committed = moved.takeIf { it != base })
    }

    /** A drag or action that has ended: the idle [state] and what, if anything, to persist. */
    data class Settled<T>(
        val state: PillDragState<T>,
        val committed: List<T>?,
    )
}
