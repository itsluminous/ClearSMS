package app.clearsms.ui.components

/**
 * Immutable multi-select state for list screens (inbox threads, conversation
 * messages). Selection mode [active] flips on via [enter] (long-press) and
 * off via [clear] (close button / system back) - or automatically when the
 * last item is deselected, so an empty contextual bar is never shown.
 */
data class SelectionState<T>(
    val selected: Set<T> = emptySet(),
    val active: Boolean = false,
) {
    val count: Int get() = selected.size

    fun isSelected(item: T): Boolean = item in selected

    /** Enters selection mode with [item] as the initial selection. */
    fun enter(item: T): SelectionState<T> = SelectionState(setOf(item), active = true)

    /** Toggles [item]; deselecting the last item exits selection mode. */
    fun toggle(item: T): SelectionState<T> {
        if (!active) return this
        val next = if (item in selected) selected - item else selected + item
        return if (next.isEmpty()) SelectionState() else copy(selected = next)
    }

    /** Adds every item in [items] to the selection (select all). */
    fun withAll(items: Collection<T>): SelectionState<T> = if (!active) this else copy(selected = selected + items)

    /** Exits selection mode, discarding the selection. */
    fun clear(): SelectionState<T> = SelectionState()
}
