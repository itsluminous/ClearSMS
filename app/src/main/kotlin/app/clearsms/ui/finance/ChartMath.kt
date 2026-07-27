package app.clearsms.ui.finance

/** Pure geometry for the hand-rolled monthly chart, kept out of the Canvas so it is unit-testable. */
object ChartMath {
    /**
     * Maps a tap x offset inside the plot area to a month index, or null when
     * the tap is outside the plot or there is nothing to hit.
     */
    fun monthIndex(
        x: Float,
        plotWidth: Float,
        monthCount: Int,
    ): Int? {
        if (monthCount <= 0 || plotWidth <= 0f || x < 0f || x >= plotWidth) return null
        return ((x / plotWidth) * monthCount).toInt().coerceAtMost(monthCount - 1)
    }

    /**
     * Value the tallest bar is scaled against. Never zero, so all-zero data
     * draws flat bars instead of dividing by zero.
     */
    fun maxValue(data: List<MonthlyTotals>): Double = data.maxOfOrNull { maxOf(it.debits, it.credits) }?.takeIf { it > 0 } ?: 1.0
}
