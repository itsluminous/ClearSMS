package app.clearsms.ui.finance

import java.time.Duration

/**
 * Splits finance entities into active and dormant by their last-updated
 * timestamp. An account or card with no update for over [STALE_AFTER] is
 * hidden behind a collapsed "Show older" control instead of cluttering the
 * active list - "no update" means no transaction/balance SMS has touched
 * the row's `lastUpdated` within the window.
 */
object StaleAccounts {
    /** Dormancy threshold: strictly more than one year without an update. */
    val STALE_AFTER: Duration = Duration.ofDays(365)

    /** True when [lastUpdatedMs] is older than [STALE_AFTER] relative to [nowMs]. */
    fun isStale(
        lastUpdatedMs: Long,
        nowMs: Long,
    ): Boolean = nowMs - lastUpdatedMs > STALE_AFTER.toMillis()

    /** Active and stale halves of [items], preserving input order. */
    fun <T> partition(
        items: List<T>,
        nowMs: Long,
        lastUpdatedMs: (T) -> Long,
    ): Partition<T> {
        val (stale, active) = items.partition { isStale(lastUpdatedMs(it), nowMs) }
        return Partition(active = active, stale = stale)
    }

    data class Partition<T>(
        val active: List<T>,
        val stale: List<T>,
    )
}
