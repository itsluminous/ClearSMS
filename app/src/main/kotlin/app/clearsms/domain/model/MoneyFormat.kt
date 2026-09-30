package app.clearsms.domain.model

import java.math.BigDecimal
import java.math.RoundingMode
import kotlin.math.abs
import kotlin.math.roundToLong

/**
 * THE amount formatter - every rupee sign the app ever showed now comes
 * from here, driven by the currency stored with the amount.
 *
 * Indian rupees render EXACTLY as they always have: `₹1,23,456.78`, lakh
 * grouping, up to two decimals with trailing zeros dropped (`₹500.5`,
 * `₹1,000`), compact axes in `k` / `L` / `Cr`. Every other currency uses its
 * own convention from [CurrencyCatalog]: `$1.000` for Chilean pesos (no
 * minor unit, `.` groups), `US$1,000.50`, `€1.000,50`, `¥1,000`; compact
 * axes in `k` / `M` / `B`. Fractions outside INR are written with the full
 * minor-unit digits when present (`US$1,000.50`) and omitted when the
 * amount is whole - the way statements in those currencies are printed.
 *
 * Lives in the domain layer so the notification code (which must not
 * depend on ui) and the Compose screens share one implementation instead of
 * the two hand-copied rupee formatters they used to carry.
 */
object MoneyFormat {
    private const val THOUSAND = 1_000.0
    private const val LAKH = 1_00_000.0
    private const val CRORE = 1_00_00_000.0
    private const val MILLION = 1_000_000.0
    private const val BILLION = 1_000_000_000.0

    /** `₹1,23,456.78` / `$1.000` / `US$40.95`; a negative value carries a leading `-`. */
    fun format(
        value: Double,
        currencyCode: String,
    ): String {
        val currency = CurrencyCatalog.of(currencyCode)
        val sign = if (value < 0) "-" else ""
        return sign + currency.symbol + grouped(value, currency)
    }

    /** Signed form for summaries: `+₹12,000` / `−₹1,23,456`; the magnitude is always absolute. */
    fun signed(
        value: Double,
        positive: Boolean,
        currencyCode: String,
    ): String {
        val currency = CurrencyCatalog.of(currencyCode)
        return (if (positive) "+" else "\u2212") + currency.symbol + grouped(value, currency)
    }

    /**
     * Compact form for chart axes and tooltips: `₹450`, `₹1.2k`, `₹45k`,
     * `₹1.2L`, `₹2.4Cr` for rupees (Indian units); `$450`, `$1.2k`, `$450k`,
     * `$1.2M`, `$2.4B` for everyone else.
     */
    fun compact(
        value: Double,
        currencyCode: String,
    ): String {
        val currency = CurrencyCatalog.of(currencyCode)
        val sign = if (value < 0) "-" else ""
        val v = abs(value)
        val symbol = currency.symbol
        // Thresholds sit at the point where one-decimal rounding would
        // overflow the unit (99,950 rounds to 100.0k, so promote).
        val body =
            if (currency.indianGrouping) {
                when {
                    v < 999.5 -> "${v.roundToLong()}"
                    v < THOUSAND * 99.95 -> "${oneDecimal(v / THOUSAND)}k"
                    v < LAKH * 99.95 -> "${oneDecimal(v / LAKH)}L"
                    else -> "${oneDecimal(v / CRORE)}Cr"
                }
            } else {
                when {
                    v < 999.5 -> "${v.roundToLong()}"
                    v < THOUSAND * 999.95 -> "${oneDecimal(v / THOUSAND)}k"
                    v < MILLION * 999.95 -> "${oneDecimal(v / MILLION)}M"
                    else -> "${oneDecimal(v / BILLION)}B"
                }
            }
        return sign + symbol + body
    }

    /**
     * The placeholder shown instead of a hidden balance: the currency's
     * symbol and fixed-width dots that carry no magnitude information.
     */
    fun mask(currencyCode: String): String = CurrencyCatalog.of(currencyCode).symbol + "\u00a0\u2022\u2022\u2022\u2022\u2022\u2022"

    /** Rounds to one decimal, dropping a trailing ".0" (1.0 → "1", 1.25 → "1.3"). */
    private fun oneDecimal(value: Double): String =
        BigDecimal(value)
            .setScale(1, RoundingMode.HALF_UP)
            .stripTrailingZeros()
            .toPlainString()

    /** Absolute value with the currency's grouping and decimal marks, no symbol. */
    private fun grouped(
        value: Double,
        currency: CurrencyInfo,
    ): String {
        val scaled = BigDecimal(abs(value)).setScale(currency.minorUnits, RoundingMode.HALF_UP)
        // INR keeps today's rendering (trailing zeros dropped: 500.50 → "500.5");
        // other currencies print the full minor unit when a fraction exists.
        val plain =
            if (currency.indianGrouping) {
                scaled.stripTrailingZeros().toPlainString()
            } else if (scaled.signum() != 0 && scaled.remainder(BigDecimal.ONE).signum() == 0) {
                scaled.setScale(0, RoundingMode.UNNECESSARY).toPlainString()
            } else if (scaled.signum() == 0) {
                "0"
            } else {
                scaled.toPlainString()
            }
        val integerPart = plain.substringBefore('.')
        val fractionPart = plain.substringAfter('.', missingDelimiterValue = "")
        val groupedInt =
            if (currency.indianGrouping) {
                indianGroups(
                    integerPart,
                    currency.groupingSeparator,
                )
            } else {
                threes(integerPart, currency.groupingSeparator)
            }
        return if (fractionPart.isEmpty()) groupedInt else "$groupedInt${currency.decimalSeparator}$fractionPart"
    }

    /** 1,23,456: the last three digits form one group, the rest pair up. */
    private fun indianGroups(
        integerPart: String,
        separator: Char,
    ): String {
        val out = StringBuilder()
        val head = if (integerPart.length > 3) integerPart.dropLast(3) else ""
        val tail = integerPart.takeLast(3)
        if (head.isNotEmpty()) {
            val pairs = ArrayDeque<String>()
            var index = head.length
            while (index > 0) {
                val start = maxOf(0, index - 2)
                pairs.addFirst(head.substring(start, index))
                index = start
            }
            out.append(pairs.joinToString(separator.toString()))
            out.append(separator)
        }
        out.append(tail)
        return out.toString()
    }

    /** 1,234,567: groups of three from the right. */
    private fun threes(
        integerPart: String,
        separator: Char,
    ): String {
        val groups = ArrayDeque<String>()
        var index = integerPart.length
        while (index > 0) {
            val start = maxOf(0, index - 3)
            groups.addFirst(integerPart.substring(start, index))
            index = start
        }
        return groups.joinToString(separator.toString())
    }
}
