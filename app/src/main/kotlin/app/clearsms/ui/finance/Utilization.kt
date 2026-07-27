package app.clearsms.ui.finance

/** Severity of credit card utilization, stepped per the finance dashboard spec. */
enum class UtilizationLevel {
    NORMAL,
    WARNING,
    DANGER,
}

/** Pure helpers around credit utilization so the color stepping is unit-testable. */
object Utilization {
    /** Fraction (0..1) of the limit used, or null when the limit is unknown/zero. */
    fun fraction(
        outstanding: Double,
        limit: Double?,
    ): Float? {
        if (limit == null || limit <= 0.0) return null
        return (outstanding / limit).toFloat().coerceIn(0f, 1f)
    }

    /** Normal below 50%, warning at 50–80%, danger at 80% and above. */
    fun level(fraction: Float): UtilizationLevel =
        when {
            fraction >= 0.80f -> UtilizationLevel.DANGER
            fraction >= 0.50f -> UtilizationLevel.WARNING
            else -> UtilizationLevel.NORMAL
        }

    /** Number of cards above the safe 30% usage threshold (drives the alert banner). */
    fun countAboveSafeLimit(fractions: List<Float?>): Int = fractions.count { it != null && it > 0.30f }
}
