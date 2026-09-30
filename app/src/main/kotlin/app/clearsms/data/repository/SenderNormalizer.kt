package app.clearsms.data.repository

/**
 * Normalizes sender addresses so messages from route variants of the same
 * sender ("VM-HDFCBK-S", "AD-HDFCBK") land in one thread.
 *
 * Two sender families, two rules, never mixed:
 * - **Phone numbers** (7+ digits, at most three non-digit characters such
 *   as `+`, spaces or dashes) reduce to their region-aware national key -
 *   see [PhoneNumberKey]. `+48 601 234 567` and `601234567` are one key.
 * - **Everything else** - alphanumeric sender ids and short codes - keeps
 *   the pre-existing rule: upper-cased, TRAI route prefix (`VM-`) and
 *   content suffix (`-S`) stripped, otherwise untouched. `56767` and
 *   `VM-HDFCBK` are never merged with each other or with a phone number.
 *
 * The phone rule depends on the device's region ([defaultRegion], set from
 * the SIM at start-up by `SenderRegion`). Unknown region = conservative:
 * international forms still reduce, national forms keep every digit.
 */
object SenderNormalizer {
    private val PREFIX_REGEX = Regex("^[A-Z]{2}-")
    private val SUFFIX_REGEX = Regex("-[SPTG]$")
    private val NON_DIGIT_REGEX = Regex("\\D")

    /**
     * ISO 3166-1 alpha-2 region every key is computed under. Process-wide
     * (not a parameter) because the one requirement on this key is that
     * EVERY consumer - threads, pins, blocked/muted sets, SIM memory -
     * computes it identically; threading a region through all of them
     * would only add ways to disagree. Null (tests, no SIM) is the
     * conservative mode described on [PhoneNumberKey.nationalKey].
     */
    @Volatile
    var defaultRegion: String? = null

    fun normalize(sender: String): String = normalize(sender, defaultRegion)

    fun normalize(
        sender: String,
        region: String?,
    ): String {
        val trimmed = sender.trim()
        if (trimmed.isEmpty()) return trimmed
        if (isPhoneNumber(trimmed)) return PhoneNumberKey.nationalKey(trimmed, region)
        return trimmed
            .uppercase()
            .replace(PREFIX_REGEX, "")
            .replace(SUFFIX_REGEX, "")
    }

    /**
     * Whether [sender] is a phone number rather than an alphanumeric id or
     * a short code: 7+ digits making up all but at most three of its
     * non-whitespace characters. THE split between the two rules above.
     */
    fun isPhoneNumber(sender: String): Boolean {
        val trimmed = sender.trim()
        val digits = trimmed.replace(NON_DIGIT_REGEX, "")
        return digits.length >= PhoneNumberKey.MIN_SUBSCRIBER_DIGITS &&
            digits.length >= trimmed.count { !it.isWhitespace() } - 3
    }

    /**
     * Whether [a] and [b] name the same sender - the membership rule for the
     * blocked- and muted-sender sets and for un-block/un-mute.
     *
     * Same current key, OR (phone numbers only) same pre-#42 key: an entry
     * stored under the old ten-digit scheme (`8601234567` for a Polish
     * number, `5112345678` for a German one) must keep matching the sender
     * it was created for, and removing it must find it again. Everything
     * the old rule matched still matches; the new key adds the variants it
     * missed (`+48…` vs local). A sender that normalizes to nothing matches
     * nothing.
     */
    fun sameSender(
        a: String,
        b: String,
    ): Boolean {
        val keyA = normalize(a)
        if (keyA.isEmpty()) return false
        val keyB = normalize(b)
        if (keyA == keyB) return true
        if (!isPhoneNumber(a) || !isPhoneNumber(b)) return false
        val legacyA = PhoneNumberKey.legacyKey(a)
        return legacyA.isNotEmpty() && legacyA == PhoneNumberKey.legacyKey(b)
    }

    /**
     * Whether [sender] (raw or normalized) matches any of [entries] under
     * [sameSender]. Entries are stored normalized but re-normalized here, so
     * a raw variant ("VM-JIOPAY" from an old backup or a hand-typed number
     * with spaces) still matches.
     */
    fun matchesAny(
        entries: Set<String>,
        sender: String,
    ): Boolean = matcher(entries)(sender)

    /**
     * [matchesAny] with the entry set pre-digested - for hot loops (the
     * bulk import checks every row of every page against the blocklist).
     */
    fun matcher(entries: Set<String>): (String) -> Boolean {
        val keys = HashSet<String>(entries.size * 2)
        val legacyKeys = HashSet<String>(entries.size * 2)
        for (entry in entries) {
            val key = normalize(entry)
            if (key.isEmpty()) continue
            keys += key
            if (isPhoneNumber(entry)) PhoneNumberKey.legacyKey(entry).takeIf { it.isNotEmpty() }?.let(legacyKeys::add)
        }
        return { sender ->
            val key = normalize(sender)
            when {
                key.isEmpty() -> false
                key in keys -> true
                !isPhoneNumber(sender) -> false
                else -> PhoneNumberKey.legacyKey(sender).let { it.isNotEmpty() && it in legacyKeys }
            }
        }
    }
}
