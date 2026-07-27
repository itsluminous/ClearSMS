package app.clearsms.ui.finance

/**
 * Pure page math for the "load more" transaction lists, so the growing-LIMIT
 * behavior and terminal state are unit-testable.
 */
object TransactionPaging {
    /** Rows fetched per page (initial LIMIT and each "load more" increment). */
    const val PAGE_SIZE = 30

    /** The next LIMIT after tapping "load more". */
    fun nextLimit(currentLimit: Int): Int = currentLimit + PAGE_SIZE

    /** True while more rows exist beyond the ones currently shown. */
    fun hasMore(
        shown: Int,
        total: Int,
    ): Boolean = shown < total

    /**
     * True while a requested page has not been delivered yet:
     * the LIMIT was raised to [requested] but only [shown] of [total]
     * rows have arrived so far.
     */
    fun isLoadingMore(
        requested: Int,
        shown: Int,
        total: Int,
    ): Boolean = requested > shown && shown < total

    /** True once the database has delivered everything the current LIMIT can produce. */
    fun pageSatisfied(
        requested: Int,
        shown: Int,
        total: Int,
    ): Boolean = shown >= minOf(requested, total)
}
