package app.clearsms.domain.model

/**
 * How amounts in one currency are WRITTEN (which separator is the decimal
 * mark, which groups digits) and SHOWN (symbol, grouping style, number of
 * minor-unit digits). Both halves matter: the writing convention decides how
 * `1.000` in a bank SMS is read - one thousand Chilean pesos, or one rupee -
 * and the display half decides that an Indian user keeps seeing
 * `₹1,23,456` while a Chilean sees `$1.000`.
 *
 * Kept as a small hand-written catalog rather than `java.util.Currency` +
 * `NumberFormat`: the platform's locale data differs between the JVM the
 * unit tests run on and the ICU build on a given phone, and a display
 * convention that changes between test and device is exactly the kind of
 * surprise a finance tab cannot afford. Unknown ISO codes fall back to a
 * generic western convention (see [CurrencyCatalog.of]) with the code
 * itself as the prefix - never an invented symbol.
 */
data class CurrencyInfo(
    /** ISO 4217 code, always upper-case ("INR", "CLP"). The stored form. */
    val code: String,
    /**
     * Display prefix. Where several currencies share `$`, the local
     * disambiguated notation is used for all but the one that owns the bare
     * sign in its home market ("US$" so an Indian user's foreign card spend
     * is never mistaken for a Chilean peso amount, "$" for CLP/USD in their
     * own homes would collide in a mixed list).
     */
    val symbol: String,
    /** Digits after the decimal mark; 0 for CLP / JPY / KRW - no fraction can exist. */
    val minorUnits: Int,
    /** The character that starts the fractional part when one is written. */
    val decimalSeparator: Char,
    /** The character that groups the integer digits. */
    val groupingSeparator: Char,
    /** Indian 2-2-3 grouping (1,23,456) versus western 3-3-3 (123,456). */
    val indianGrouping: Boolean,
) {
    /** True when a fractional part is impossible in this currency. */
    val zeroMinorUnits: Boolean get() = minorUnits == 0
}

/** The bundled currency conventions, keyed by ISO code. */
object CurrencyCatalog {
    /** The app's historic default: every amount ever stored before the currency column existed. */
    const val INR_CODE = "INR"

    private fun western(
        code: String,
        symbol: String,
        minorUnits: Int = 2,
    ) = CurrencyInfo(
        code = code,
        symbol = symbol,
        minorUnits = minorUnits,
        decimalSeparator = '.',
        groupingSeparator = ',',
        indianGrouping = false,
    )

    /** Continental convention: `.` groups, `,` is the decimal mark (1.000,50). */
    private fun continental(
        code: String,
        symbol: String,
        minorUnits: Int = 2,
    ) = CurrencyInfo(
        code = code,
        symbol = symbol,
        minorUnits = minorUnits,
        decimalSeparator = ',',
        groupingSeparator = '.',
        indianGrouping = false,
    )

    val INR: CurrencyInfo =
        CurrencyInfo(
            code = INR_CODE,
            symbol = "\u20b9",
            minorUnits = 2,
            decimalSeparator = '.',
            groupingSeparator = ',',
            indianGrouping = true,
        )

    private val known: Map<String, CurrencyInfo> =
        listOf(
            INR,
            // Zero-minor-unit currencies: a fractional part cannot exist, so
            // every separator in an amount is a digit grouper.
            continental("CLP", "$", minorUnits = 0),
            western("JPY", "\u00a5", minorUnits = 0),
            western("KRW", "\u20a9", minorUnits = 0),
            continental("PYG", "\u20b2", minorUnits = 0),
            continental("VND", "\u20ab", minorUnits = 0),
            western("USD", "US$"),
            continental("EUR", "\u20ac"),
            western("GBP", "\u00a3"),
            western("AED", "AED\u00a0"),
            western("SGD", "S$"),
            western("AUD", "A$"),
            western("CAD", "C$"),
            continental("CHF", "CHF\u00a0"),
            western("NZD", "NZ$"),
            western("HKD", "HK$"),
            western("CNY", "CN\u00a5"),
            western("MYR", "RM"),
            western("THB", "\u0e3f"),
            western("PHP", "\u20b1"),
            western("SAR", "SAR\u00a0"),
            western("QAR", "QAR\u00a0"),
            western("KWD", "KWD\u00a0", minorUnits = 3),
            western("BHD", "BHD\u00a0", minorUnits = 3),
            western("OMR", "OMR\u00a0", minorUnits = 3),
            western("ZAR", "R"),
            western("NGN", "\u20a6"),
            western("KES", "KSh\u00a0"),
            western("LKR", "Rs\u00a0"),
            western("NPR", "Rs\u00a0"),
            western("PKR", "Rs\u00a0"),
            western("BDT", "\u09f3"),
            western("MXN", "MX$"),
            continental("BRL", "R$"),
            continental("ARS", "AR$"),
            continental("COP", "COL$"),
            western("PEN", "S/\u00a0"),
            continental("UYU", "\$U\u00a0"),
            continental("TRY", "\u20ba"),
            continental("RUB", "\u20bd"),
            continental("IDR", "Rp\u00a0"),
            continental("SEK", "kr\u00a0"),
            continental("NOK", "kr\u00a0"),
            continental("DKK", "kr\u00a0"),
            continental("PLN", "z\u0142\u00a0"),
            continental("CZK", "K\u010d\u00a0"),
            continental("HUF", "Ft\u00a0", minorUnits = 0),
        ).associateBy { it.code }

    /** Every code the catalog knows, upper-case, for regex construction and the settings picker. */
    val codes: Set<String> get() = known.keys

    /**
     * The convention for [code] (case-insensitive). An unknown or blank code
     * yields a generic western convention whose prefix is the code itself
     * ("XYZ 1,000.50") - the honest rendering for a currency we know nothing
     * about, never a guessed symbol and never a silent fallback to rupees.
     */
    fun of(code: String?): CurrencyInfo {
        val normalized = code?.trim()?.uppercase().orEmpty()
        if (normalized.isEmpty()) return INR
        return known[normalized] ?: western(normalized, "$normalized\u00a0")
    }

    /** True when [code] names a currency this catalog describes. */
    fun isKnown(code: String?): Boolean = code?.trim()?.uppercase() in known
}
