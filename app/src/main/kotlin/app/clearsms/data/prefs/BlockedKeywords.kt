package app.clearsms.data.prefs

/**
 * Policy for the user's blocked-keyword list: what counts as a match at
 * ingestion, and what may enter the list in the first place.
 *
 * Matching is a case-insensitive SUBSTRING test against the message body -
 * deliberately simple so users can predict it ("LOAN OFFER" catches
 * "Pre-approved loan offer inside!"). Because a substring rule is a
 * foot-gun at short lengths (a 1-character keyword would bin everything),
 * [validate] refuses blank and single-character keywords and caps the list
 * at [MAX_COUNT] so ingestion never scans an unbounded set.
 */
object BlockedKeywords {
    /** Minimum keyword length; anything shorter matches far too much. */
    const val MIN_LENGTH = 2

    /** Hard cap on the list size (every incoming message scans the list). */
    const val MAX_COUNT = 100

    /** Why a candidate keyword was refused. */
    enum class ValidationError {
        TOO_SHORT,
        DUPLICATE,
        LIMIT_REACHED,
    }

    /** Refusal reason for adding [keyword] to [existing], or null when fine. */
    fun validate(
        keyword: String,
        existing: Set<String>,
    ): ValidationError? {
        val trimmed = keyword.trim()
        return when {
            trimmed.length < MIN_LENGTH -> ValidationError.TOO_SHORT
            existing.any { it.equals(trimmed, ignoreCase = true) } -> ValidationError.DUPLICATE
            existing.size >= MAX_COUNT -> ValidationError.LIMIT_REACHED
            else -> null
        }
    }

    /** Whether [body] contains any of [keywords], case-insensitively. */
    fun matches(
        body: String,
        keywords: Set<String>,
    ): Boolean = keywords.any { keyword -> keyword.isNotBlank() && body.contains(keyword.trim(), ignoreCase = true) }
}
