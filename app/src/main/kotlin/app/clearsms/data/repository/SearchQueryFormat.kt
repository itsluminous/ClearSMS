package app.clearsms.data.repository

/**
 * Turns raw user input into a safe FTS4 MATCH expression.
 *
 * The FTS index uses the `simple` tokenizer, which splits on any
 * non-alphanumeric character — so user punctuation is treated as a
 * separator here too, which doubles as sanitization: no quote, `-`, `*`,
 * `OR`/`NEAR` operator or column-filter syntax survives into the MATCH
 * expression. Each token gets a trailing `*` for search-as-you-type prefix
 * matching, and tokens are joined by space (implicit AND).
 */
object SearchQueryFormat {
    /**
     * Queries shorter than this never hit the database: a 1-character
     * prefix matches most of the token index (measured ~95% of a 14.5k-row
     * corpus for "s"), which is expensive to page and useless as a result
     * list.
     */
    const val MIN_QUERY_LENGTH = 2

    /** Alphanumeric tokens of [raw] (what the FTS tokenizer would see). */
    fun tokens(raw: String): List<String> =
        raw
            .split(NON_ALPHANUMERIC)
            .filter { it.isNotEmpty() }

    /** MATCH expression for [raw], or null when it holds no tokens. */
    fun toFtsMatch(raw: String): String? {
        val parts = tokens(raw)
        if (parts.isEmpty()) return null
        return parts.joinToString(" ") { "$it*" }
    }

    /** Whether [raw] is long enough and tokenizable — the query gate. */
    fun isSearchable(raw: String): Boolean = raw.trim().length >= MIN_QUERY_LENGTH && tokens(raw).isNotEmpty()

    private val NON_ALPHANUMERIC = Regex("[^\\p{L}\\p{N}]+")
}
