package app.clearsms.ui.common

import java.math.BigDecimal
import java.math.RoundingMode
import kotlin.math.abs

/**
 * Rupee amount formatting with Indian digit grouping (₹1,23,456.78):
 * the last three integer digits form one group, the rest pair up.
 * Implemented by hand because DecimalFormat only honors a single group size.
 */
object CurrencyFormat {
    private fun grouped(value: Double): String {
        val rounded = BigDecimal(abs(value)).setScale(2, RoundingMode.HALF_UP).stripTrailingZeros()
        val plain = rounded.toPlainString()
        val integerPart = plain.substringBefore('.')
        val fractionPart = plain.substringAfter('.', missingDelimiterValue = "")

        val groupedInt = StringBuilder()
        val head = if (integerPart.length > 3) integerPart.dropLast(3) else ""
        val tail = integerPart.takeLast(3)
        if (head.isNotEmpty()) {
            // Pairs from the right of the head.
            val pairs = ArrayDeque<String>()
            var index = head.length
            while (index > 0) {
                val start = maxOf(0, index - 2)
                pairs.addFirst(head.substring(start, index))
                index = start
            }
            groupedInt.append(pairs.joinToString(","))
            groupedInt.append(',')
        }
        groupedInt.append(tail)
        return if (fractionPart.isEmpty()) groupedInt.toString() else "$groupedInt.$fractionPart"
    }

    fun rupees(value: Double): String {
        val sign = if (value < 0) "-" else ""
        return "$sign₹${grouped(value)}"
    }

    /** Signed form used in monthly summaries: "+₹12,000" / "−₹1,23,456". */
    fun signedRupees(
        value: Double,
        positive: Boolean,
    ): String = (if (positive) "+" else "−") + "₹" + grouped(value)
}
