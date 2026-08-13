package app.clearsms.ui.alerts

/**
 * Windowed rendering for the "Older alerts" section. Older is a complete,
 * unbounded archive (no auto-purge), so the screen must never compose
 * thousands of cards eagerly: it renders [PAGE_SIZE] at a time and grows
 * per "Show more" click. Reminder rows themselves are tiny derived
 * records (a fraction of the message table the inbox pages with Room), so
 * holding the LIST in memory is fine - the cap is on composition.
 */
object OlderPaging {
    /** Older-alerts cards rendered per "Show more" click. */
    const val PAGE_SIZE = 20

    /** How many cards to actually compose for [total] rows when [requested] are wanted. */
    fun visibleCount(
        total: Int,
        requested: Int,
    ): Int = requested.coerceIn(0, total)

    /** How many rows remain hidden behind "Show more". */
    fun remaining(
        total: Int,
        requested: Int,
    ): Int = (total - requested).coerceAtLeast(0)

    /** The next window size after a "Show more" click. */
    fun grow(requested: Int): Int = requested + PAGE_SIZE
}
