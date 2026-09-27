package app.clearsms.ui.inbox

import app.clearsms.domain.model.Category
import app.clearsms.domain.model.MessageSortOrder
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged

/**
 * Exactly the inputs that change the SQL behind the paged inbox - nothing
 * else. This is the ONLY thing the inbox `Pager` is rebuilt from.
 *
 * Why it exists: the pager sits behind `flatMapLatest`, which destroys the
 * running `Pager` and starts a fresh `PagingData` stream (from the top)
 * every time its upstream emits. Presentational state - unread counts,
 * contact names, banners, a settings flow re-emitting the value it already
 * had - must never be able to do that. Anything that changes only how
 * loaded rows LOOK goes through an in-place `PagingSource` invalidation
 * instead (Room does this itself on every write), which Paging refreshes
 * around the anchor position without a new stream.
 *
 * A plain `data class` so two keys built from equal query inputs are
 * `equals`, letting [inboxPagerKeys]' `distinctUntilChanged` drop repeats.
 */
data class InboxPagerKey(
    /** Category pill in force, null for All - see [InboxFilterState.category]. */
    val category: Category?,
    val unreadOnly: Boolean,
    val sortOrder: MessageSortOrder,
) {
    companion object {
        fun of(
            filter: InboxFilterState,
            sortOrder: MessageSortOrder,
        ): InboxPagerKey = InboxPagerKey(filter.category, filter.unreadOnly, sortOrder)
    }
}

/**
 * The pager's upstream: query keys derived from the effective filter and
 * the sort order, with equal consecutive keys suppressed. Every emission
 * from this flow rebuilds the pager, so an emission here must mean the
 * query text genuinely changed.
 *
 * The `distinctUntilChanged` is load-bearing for [sortOrders]: the
 * DataStore-backed settings flows re-emit on ANY preference write (they
 * are `data.map { it[KEY] }` with no de-duplication), so without it a
 * write to an unrelated setting would rebuild the pager.
 */
fun inboxPagerKeys(
    filters: Flow<InboxFilterState>,
    sortOrders: Flow<MessageSortOrder>,
): Flow<InboxPagerKey> = combine(filters, sortOrders, InboxPagerKey::of).distinctUntilChanged()
