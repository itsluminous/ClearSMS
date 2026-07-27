package app.clearsms.data.repository

/**
 * Normalizes sender addresses so messages from route variants of the same
 * sender ("VM-HDFCBK-S", "AD-HDFCBK") land in one thread.
 */
object SenderNormalizer {
    private val PREFIX_REGEX = Regex("^[A-Z]{2}-")
    private val SUFFIX_REGEX = Regex("-[SPTG]$")
    private val NON_DIGIT_REGEX = Regex("\\D")

    fun normalize(sender: String): String {
        val trimmed = sender.trim()
        if (trimmed.isEmpty()) return trimmed
        val digits = trimmed.replace(NON_DIGIT_REGEX, "")
        // Phone numbers: compare by the last 10 digits so "+91 98765 43210"
        // and "9876543210" share a thread.
        if (digits.length >= 7 && digits.length >= trimmed.count { !it.isWhitespace() } - 3) {
            return digits.takeLast(10)
        }
        return trimmed
            .uppercase()
            .replace(PREFIX_REGEX, "")
            .replace(SUFFIX_REGEX, "")
    }
}
