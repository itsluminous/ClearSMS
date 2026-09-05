package app.clearsms.ui.components

/**
 * The single judgement of whether a digit string is a number a person would
 * dial. Shared by message-body link detection ([BodyLinkFinder]) and the
 * conversation top bar's call / save-contact affordances, so the two
 * surfaces can never disagree about what is dialable.
 */
object DialableNumber {
    /**
     * The dialable form of a candidate, or null when it is not a number a
     * person would call:
     *
     * - with a country code: `+` then 8-15 digits (the E.164 range);
     * - a bare Indian mobile: exactly 10 digits starting 6-9;
     * - a bare toll-free line: 11 digits starting 1800.
     *
     * Everything else - 11-digit transaction ids, 16-digit cards, 6-digit
     * dates and PINs, alphanumeric TRAI sender ids ("HDFCBK") and short
     * codes ("139", "56767") - is left alone. The 10-digit PNR case (whose
     * SHAPE is a legitimate mobile) is caught by [BodyLinkFinder]'s
     * reference-word check at its call site. Spaces and hyphens inside the
     * candidate are tolerated ("98765 43210", "98765-43210").
     */
    fun of(candidate: String): String? {
        val compact = candidate.filter { !it.isWhitespace() && it != '-' }
        val digits = compact.removePrefix("+")
        if (digits.isEmpty() || digits.any { !it.isDigit() }) return null
        return when {
            compact.startsWith("+") && digits.length in 8..15 -> compact
            digits.length == 10 && digits.first() in '6'..'9' -> digits
            digits.length == 11 && digits.startsWith("1800") -> digits
            else -> null
        }
    }
}
