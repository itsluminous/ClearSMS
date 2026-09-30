package app.clearsms.domain.parser

import app.clearsms.domain.model.CurrencyCatalog
import app.clearsms.domain.model.CurrencyInfo

/**
 * Reads a written amount ("1,000.50", "1.000", "1.000,50", "1,00,000") as a
 * number, using the CURRENCY's writing convention instead of assuming that
 * `,` groups digits and `.` starts the fraction. That assumption is what
 * turned a Chilean `$1.000` (one thousand pesos) into 1.0 and made a whole
 * finance tab wrong by a factor of 1000 (issue #65).
 *
 * The rule, applied in this order - deliberately simple so a reader can
 * predict every outcome:
 *
 * 1. ZERO-MINOR-UNIT currency (CLP, JPY, KRW, ...): a fractional part cannot
 *    exist, so every separator groups digits. `1.000` = 1000, `1.000.500` =
 *    1000500, `1,000` = 1000. This settles most of the ambiguity outright.
 * 2. BOTH separators present: the one that occurs LAST is the decimal mark,
 *    the other groups. `1.000,50` = 1000.50 and `1,000.50` = 1000.50 - no
 *    convention needed; the string is self-describing.
 * 3. ONE separator kind, occurring MORE THAN ONCE: it groups digits.
 *    `1.000.500` = 1000500, `1,00,000` = 100000.
 * 4. ONE separator, occurring ONCE, followed by EXACTLY THREE digits (and
 *    the currency has fewer than three minor units): it groups. A three-digit
 *    tail with no other decimal marker in sight is a thousands group in every
 *    real bank message we have seen; a written fraction has one or two
 *    digits. `USD 1.000` = 1000 even though `.` is USD's decimal mark.
 * 5. ONE separator, occurring ONCE, any other digit count: the CURRENCY's
 *    convention decides. Its decimal mark starts the fraction (`INR 1.50` =
 *    1.5, `EUR 1,50` = 1.5); its grouping mark groups (`INR 1,5` = 15).
 *
 * Under this rule every INR amount the app has ever parsed reads exactly as
 * before (INR's marks are `,` groups / `.` decimal, and the amount regexes
 * only ever admit one or two decimal digits, so rule 4 never fires for them).
 * Returns null for anything that is not a plain number - a leading
 * separator, letters, two decimal marks; a TRAILING separator is sentence
 * punctuation the capture swallowed ("Rs.500,") and is dropped.
 */
object AmountParser {
    private val SHAPE = Regex("\\d[\\d.,]*\\d|\\d")

    fun parse(
        raw: String,
        currency: CurrencyInfo,
    ): Double? {
        // A trailing separator is sentence punctuation the capture swallowed
        // ("Rs.500, at ..."), never part of the number.
        val text = raw.trim().trimEnd(',', '.')
        if (!SHAPE.matches(text)) return null
        val dots = text.count { it == '.' }
        val commas = text.count { it == ',' }
        if (dots == 0 && commas == 0) return text.toDoubleOrNull()

        val decimal: Char? =
            when {
                // 1. No fraction can exist: every separator groups. When both
                //    kinds nevertheless appear ("1.000,50" in a CLP body) the
                //    last one can only be a stray decimal mark; it is read as
                //    one and its fraction dropped below, so the figure is 1000
                //    - never 100050.
                currency.zeroMinorUnits && (dots == 0 || commas == 0) -> null
                // 2. Both present: the last one is the decimal mark.
                dots > 0 && commas > 0 -> if (text.lastIndexOf('.') > text.lastIndexOf(',')) '.' else ','
                else -> {
                    val separator = if (dots > 0) '.' else ','
                    val occurrences = if (dots > 0) dots else commas
                    val tailDigits = text.length - text.lastIndexOf(separator) - 1
                    when {
                        // 3. Repeated: groups.
                        occurrences > 1 -> null
                        // 4. Exactly three digits after the only separator: groups.
                        tailDigits == 3 && currency.minorUnits < 3 -> null
                        // 5. The currency's own convention.
                        separator == currency.decimalSeparator -> separator
                        else -> null
                    }
                }
            }
        // A decimal mark that appears more than once is not a number.
        if (decimal != null && text.count { it == decimal } > 1) return null
        val normalized =
            buildString(text.length) {
                for (ch in text) {
                    when {
                        ch == decimal -> append('.')
                        ch == '.' || ch == ',' -> Unit
                        else -> append(ch)
                    }
                }
            }
        val value = normalized.toDoubleOrNull() ?: return null
        // A zero-minor-unit currency has no fraction to keep.
        return if (currency.zeroMinorUnits) kotlin.math.floor(value) else value
    }

    /** Convenience for callers holding only an ISO code. */
    fun parse(
        raw: String,
        currencyCode: String,
    ): Double? = parse(raw, CurrencyCatalog.of(currencyCode))
}
