package app.clearsms.data.repository

import java.text.Normalizer

/** How an address earned its place in the sender-match set. */
enum class SenderMatchReason {
    /** The user's saved contact name matched the query. */
    CONTACT_NAME,

    /** The resolved sender display name (directory / corrections / brands) matched. */
    SENDER_NAME,

    /** The raw address / sender ID itself matched. */
    SENDER_ID,
}

/** Sender addresses (exact DB `sender` strings) a query names, with why. */
data class SenderMatches(
    val reasons: Map<String, SenderMatchReason>,
) {
    val addresses: List<String> get() = reasons.keys.toList()

    companion object {
        val EMPTY = SenderMatches(emptyMap())
    }
}

/**
 * Resolves a search query to the sender ADDRESSES it names, so that the
 * message search can stay a single SQL query: bodies via the FTS index,
 * OR `sender IN (resolved addresses)` - one row per message, no post-merge
 * deduplication, and category/date filters and paging untouched.
 *
 * Contact names live in the contacts provider, not in the messages
 * database, so the join happens here: ONE forward provider query (injected
 * as [contactNumbersMatching], never executed on the main thread by the
 * caller) resolves matching contacts to phone numbers, which are joined to
 * the corpus's distinct senders by the same last-10-digit key the contact
 * cache uses - so `+91 98765 43210`, `09876543210` and `9876543210` all
 * meet. Resolved sender names ([resolvedName], the sender-ID directory +
 * corrections + brand table) and the raw address are matched directly.
 *
 * Matching mirrors the FTS semantics the body search uses: every query
 * token must prefix some word of the candidate (order-free AND), compared
 * case-insensitively with diacritics folded ("cafe" matches "Café").
 *
 * Pure: all sources are injected, so the join is unit-testable with a fake
 * contact source.
 */
object SenderQueryResolver {
    /**
     * Cap on resolved addresses: keeps the SQL `IN` list well under
     * SQLite's 999-variable limit even with the other bound parameters. A
     * name query realistically resolves a handful of addresses; hitting
     * this cap means the query was so generic the extra rows add noise,
     * not signal.
     */
    const val MAX_MATCHES = 300

    fun resolve(
        query: String,
        senders: List<String>,
        contactNumbersMatching: (String) -> List<String>,
        resolvedName: (String) -> String?,
    ): SenderMatches {
        val tokens = SearchQueryFormat.tokens(query).map { fold(it) }
        if (tokens.isEmpty()) return SenderMatches.EMPTY
        val reasons = LinkedHashMap<String, SenderMatchReason>()

        // Contact names: join provider numbers to DB senders by digit key.
        // The provider query is skipped entirely when no sender is a phone
        // number (an all-service-sender corpus never touches contacts).
        val byDigits = HashMap<String, MutableList<String>>()
        for (sender in senders) {
            val key = digitKey(sender) ?: continue
            byDigits.getOrPut(key) { mutableListOf() }.add(sender)
        }
        if (byDigits.isNotEmpty()) {
            for (number in contactNumbersMatching(query)) {
                if (reasons.size >= MAX_MATCHES) break
                val matched = byDigits[digitKey(number) ?: continue] ?: continue
                for (address in matched) {
                    if (address !in reasons) reasons[address] = SenderMatchReason.CONTACT_NAME
                }
            }
        }

        // Resolved sender names, then the raw address itself.
        for (sender in senders) {
            if (reasons.size >= MAX_MATCHES) break
            if (sender in reasons) continue
            val name = resolvedName(sender)
            if (name != null && matchesTokens(tokens, name)) {
                reasons[sender] = SenderMatchReason.SENDER_NAME
            } else if (matchesTokens(tokens, sender)) {
                reasons[sender] = SenderMatchReason.SENDER_ID
            }
        }
        return if (reasons.isEmpty()) SenderMatches.EMPTY else SenderMatches(reasons)
    }

    /**
     * Whether [text] matches [query] under the same token-prefix semantics -
     * used to tell a sender-only hit (body silent on the query) apart from
     * a hit the body already explains.
     */
    fun textMatches(
        query: String,
        text: String,
    ): Boolean {
        val tokens = SearchQueryFormat.tokens(query).map { fold(it) }
        return tokens.isNotEmpty() && matchesTokens(tokens, text)
    }

    private fun matchesTokens(
        foldedQueryTokens: List<String>,
        candidate: String,
    ): Boolean {
        val words = SearchQueryFormat.tokens(candidate).map { fold(it) }
        if (words.isEmpty()) return false
        return foldedQueryTokens.all { token -> words.any { it.startsWith(token) } }
    }

    /**
     * Format-insensitive phone key (last 10 digits), or null for addresses
     * that are not plausibly phone numbers - mirrors the contact cache key.
     */
    private fun digitKey(address: String): String? {
        val trimmed = address.trim()
        if (trimmed.isEmpty()) return null
        if (!trimmed.all { it.isDigit() || it in "+-() ." }) return null
        val digits = trimmed.filter { it.isDigit() }
        if (digits.length < MIN_PHONE_DIGITS) return null
        return digits.takeLast(PHONE_KEY_DIGITS)
    }

    private fun fold(value: String): String = Normalizer.normalize(value.lowercase(), Normalizer.Form.NFD).replace(COMBINING_MARKS, "")

    private val COMBINING_MARKS = Regex("\\p{Mn}+")
    private const val MIN_PHONE_DIGITS = 5
    private const val PHONE_KEY_DIGITS = 10
}
