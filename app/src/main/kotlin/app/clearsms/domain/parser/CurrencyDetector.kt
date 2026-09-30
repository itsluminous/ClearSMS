package app.clearsms.domain.parser

import app.clearsms.domain.model.CurrencyCatalog

/**
 * Where the currency of an amount comes from when the message itself does
 * not settle it.
 *
 * @property deviceCurrency ISO code implied by the SIM's country, else the
 *   device locale - the user's home market. The FALLBACK, never the answer
 *   when the message names a currency.
 * @property override ISO code the user forced in Settings (Finance →
 *   Currency), or null for automatic detection. The honest safety net for
 *   when detection is unreliable: it replaces the device fallback AND
 *   decides every ambiguous marker (`$`, `Rs`, `¥`), but an unambiguous
 *   marker in the message (`₹`, `€`, an ISO code next to a number) still
 *   wins - an Indian user's "Spent USD 40.95" is US dollars whatever the
 *   setting says.
 */
data class CurrencyContext(
    val deviceCurrency: String = CurrencyCatalog.INR_CODE,
    val override: String? = null,
) {
    /** The currency an amount gets when the message offers no usable evidence. */
    val fallback: String get() = override ?: deviceCurrency

    companion object {
        /** The app's historic behaviour: rupees unless the message says otherwise. */
        val INDIA = CurrencyContext()
    }
}

/**
 * Decides which currency the amounts in a message are denominated in.
 *
 * Evidence, strongest first:
 * 1. An UNAMBIGUOUS marker in the message - `₹` / `INR`, a known ISO code
 *    written next to a number (`USD 40.95`, `1.000 CLP`), or a symbol only
 *    one catalog currency uses (`€`, `£`, `₩`, `R$`, `US$`, `CLP$`). The
 *    message wins over the device locale and over the Settings override:
 *    the bank knows what it charged.
 * 2. An AMBIGUOUS marker shared by a family of currencies - a bare `$`
 *    (Chile, the US, Australia, Mexico and a dozen others), `Rs` (Indian,
 *    Sri Lankan, Nepali, Pakistani rupees), `¥` (yen or yuan): resolved by
 *    [CurrencyContext.fallback] (the Settings override when set, else the
 *    SIM/locale currency) when that is a member of the family; otherwise the
 *    family's default (USD, INR, JPY).
 * 3. No marker at all: [CurrencyContext.fallback].
 *
 * When a message carries more than one unambiguous marker, the FIRST one in
 * reading order wins - the leading amount is the transaction; a trailing
 * "Avl Limit: INR ..." is state. ISO codes are matched only in upper case
 * AND only next to a digit, so "PLEASE TRY AGAIN" can never be Turkish lira
 * and "PEN" in a caps-lock body is never a Peruvian sol.
 */
object CurrencyDetector {
    /** Symbols that name exactly one catalog currency. */
    private val UNIQUE_SYMBOLS: List<Pair<Regex, String>> =
        listOf(
            Regex("(?i)\\u20b9|(?<![A-Za-z])INR(?![A-Za-z])") to "INR",
            Regex("\\u20ac") to "EUR",
            Regex("\\u00a3") to "GBP",
            Regex("\\u20a9") to "KRW",
            Regex("\\u20ba") to "TRY",
            Regex("\\u20bd") to "RUB",
            Regex("\\u20a6") to "NGN",
            Regex("\\u20b1") to "PHP",
            Regex("\\u0e3f") to "THB",
            Regex("(?<![A-Za-z])US\\$") to "USD",
            Regex("(?<![A-Za-z])R\\$") to "BRL",
            Regex("(?<![A-Za-z])CLP\\$") to "CLP",
            Regex("(?<![A-Za-z])CL\\$") to "CLP",
            Regex("(?<![A-Za-z])A\\$") to "AUD",
            Regex("(?<![A-Za-z])C\\$") to "CAD",
            Regex("(?<![A-Za-z])NZ\\$") to "NZD",
            Regex("(?<![A-Za-z])S\\$") to "SGD",
            Regex("(?<![A-Za-z])HK\\$") to "HKD",
            Regex("(?<![A-Za-z])MX\\$") to "MXN",
            Regex("(?<![A-Za-z])AR\\$") to "ARS",
            Regex("(?<![A-Za-z])COL\\$") to "COP",
        )

    /** A marker shared by a family: (regex, the family, its default member). */
    private class Family(
        val regex: Regex,
        val members: Set<String>,
        val default: String,
    )

    private val FAMILIES: List<Family> =
        listOf(
            Family(
                regex = Regex("(?<![A-Za-z])\\$"),
                members = setOf("USD", "CLP", "AUD", "CAD", "NZD", "SGD", "HKD", "MXN", "ARS", "COP", "UYU"),
                default = "USD",
            ),
            Family(
                regex = Regex("(?i)(?<![A-Za-z])Rs\\.?(?=\\s*\\d)"),
                members = setOf("INR", "LKR", "NPR", "PKR"),
                default = "INR",
            ),
            Family(
                regex = Regex("\\u00a5"),
                members = setOf("JPY", "CNY"),
                default = "JPY",
            ),
        )

    /**
     * A known ISO code, upper case, standing as its own word and touching a
     * number on at least one side ("USD 40.95", "1.000 CLP", "INR1500").
     */
    private val ISO_CODE_REGEX: Regex =
        run {
            val codes = CurrencyCatalog.codes.sorted().joinToString("|")
            Regex("(?<![A-Za-z])($codes)(?![A-Za-z])(?=\\s*\\d)|(?<=\\d)\\s*(?<![A-Za-z])($codes)(?![A-Za-z])")
        }

    /** ISO code of the currency the amounts in [body] are written in. */
    fun detect(
        body: String,
        context: CurrencyContext,
    ): String {
        var best: Pair<Int, String>? = null
        for ((regex, code) in UNIQUE_SYMBOLS) {
            val at = regex.find(body)?.range?.first ?: continue
            if (best == null || at < best.first) best = at to code
        }
        ISO_CODE_REGEX.find(body)?.let { match ->
            val code = match.groupValues[1].ifEmpty { match.groupValues[2] }
            if (best == null || match.range.first < best!!.first) best = match.range.first to code
        }
        best?.let { return it.second }
        for (family in FAMILIES) {
            if (!family.regex.containsMatchIn(body)) continue
            return context.fallback.takeIf { it in family.members } ?: family.default
        }
        return context.fallback
    }
}
