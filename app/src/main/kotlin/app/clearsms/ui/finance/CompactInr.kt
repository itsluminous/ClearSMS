package app.clearsms.ui.finance

import java.math.BigDecimal
import java.math.RoundingMode
import kotlin.math.abs
import kotlin.math.roundToLong

/**
 * Compact rupee formatting for chart axes and tooltips: ₹450, ₹1.2k, ₹45k,
 * ₹1.2L, ₹2.4Cr. Indian units (lakh = 1,00,000 and crore = 1,00,00,000)
 * because bank SMS amounts are INR.
 */
object CompactInr {
    private const val THOUSAND = 1_000.0
    private const val LAKH = 1_00_000.0
    private const val CRORE = 1_00_00_000.0

    fun format(value: Double): String {
        val sign = if (value < 0) "-" else ""
        val v = abs(value)
        // Thresholds sit at the point where one-decimal rounding would
        // overflow the unit (99,950 rounds to 100.0k, so promote to 1L).
        return sign +
            when {
                v < 999.5 -> "₹${v.roundToLong()}"
                v < THOUSAND * 99.95 -> "₹${oneDecimal(v / THOUSAND)}k"
                v < LAKH * 99.95 -> "₹${oneDecimal(v / LAKH)}L"
                else -> "₹${oneDecimal(v / CRORE)}Cr"
            }
    }

    /** Rounds to one decimal, dropping a trailing ".0" (1.0 → "1", 1.25 → "1.3"). */
    private fun oneDecimal(value: Double): String =
        BigDecimal(value)
            .setScale(1, RoundingMode.HALF_UP)
            .stripTrailingZeros()
            .toPlainString()
}
