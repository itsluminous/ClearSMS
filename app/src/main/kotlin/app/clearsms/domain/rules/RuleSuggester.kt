package app.clearsms.domain.rules

/** Kinds of tappable tokens the suggester detects in a source message. */
enum class TokenKind {
    AMOUNT,
    BALANCE,
    ACCOUNT_LAST4,
    OTP_CODE,
    DATE,
    REFERENCE,
    PERCENT,
    GENERIC_NUMBER,
    VENDOR,
    KEYWORD,
}

/**
 * A candidate token detected in a message body.
 *
 * @property literal the exact text shown to the user (for numeric tokens, the digits).
 * @property start start of the span in the body that [captureFragment] replaces.
 * @property end exclusive end of that span.
 * @property captureFragment regex fragment with EXACTLY ONE capturing group that is
 *   substituted at the span when the user chooses to capture this token. Fragments are
 *   bounded (no unbounded nested quantifiers, no catch-all wrappers).
 * @property suggestedField pre-selected extract field key, or null for "ignore".
 * @property rank preference order within a kind (lower is better); used to rank
 *   VENDOR candidates.
 */
data class SuggestedToken(
    val kind: TokenKind,
    val literal: String,
    val start: Int,
    val end: Int,
    val captureFragment: String,
    val suggestedField: String? = null,
    val rank: Int = 0,
)

/**
 * Analyzes a concrete SMS and produces structured, tappable suggestions so the
 * user never has to write regex by hand: currency amounts, masked account
 * tails, OTP codes, dates, reference ids, balances, vendor-name candidates,
 * leftover digit groups and salient keywords - each with the exact regex
 * fragment the composer will substitute for it.
 *
 * Pure Kotlin (no Android dependencies) so every detection path is unit-testable
 * on the JVM.
 */
object RuleSuggester {
    /** Extract field keys the wizard can map captured tokens onto. */
    object Fields {
        const val AMOUNT = "amount"
        const val ACCOUNT_LAST4 = "account_last4"
        const val OTP_CODE = "otp_code"
        const val BALANCE = "balance"
        const val BANK = "bank"
        const val MERCHANT = "merchant"
        const val REFERENCE = "reference"
        const val DUE_DATE = "due_date"

        val ALL = listOf(AMOUNT, ACCOUNT_LAST4, OTP_CODE, BALANCE, BANK, MERCHANT, REFERENCE, DUE_DATE)
    }

    private const val AMOUNT_FRAGMENT = """(?:INR|Rs\.?|₹)\s*([\d,]+(?:\.\d{1,2})?)"""
    private val AMOUNT_REGEX = Regex("(?i)$AMOUNT_FRAGMENT")

    private const val BALANCE_FRAGMENT =
        """(?:avl\.?\s*bal(?:ance)?|available\s+bal(?:ance)?)\s*:?\s*(?:is\s+)?(?:INR|Rs\.?|₹)?\s*([\d,]+(?:\.\d{1,2})?)"""
    private val BALANCE_REGEX = Regex("(?i)$BALANCE_FRAGMENT")

    private const val ACCOUNT_FRAGMENT =
        """(?:a/c|acct|account|card)\s*(?:no\.?|number)?\s*(?:ending(?:\s+in)?)?\s*[Xx*]*(\d{3,4})\b"""
    private val ACCOUNT_REGEX = Regex("(?i)\\b$ACCOUNT_FRAGMENT")

    private const val REFERENCE_FRAGMENT =
        """(?:ref(?:erence)?|utr|txn|transaction)\b[\s.:#-]*(?:no\.?|number|id)?[\s.:#-]*([A-Za-z0-9]{6,25})\b"""
    private val REFERENCE_REGEX = Regex("(?i)\\b$REFERENCE_FRAGMENT")

    private const val DATE_MONTH_FRAGMENT = """(\d{1,2}-[A-Za-z]{3}-\d{2,4})"""
    private val DATE_MONTH_REGEX = Regex("\\b$DATE_MONTH_FRAGMENT\\b")
    private const val DATE_NUMERIC_FRAGMENT = """(\d{1,2}[-/]\d{1,2}(?:[-/]\d{2,4})?)"""
    private val DATE_NUMERIC_REGEX = Regex("\\b$DATE_NUMERIC_FRAGMENT\\b")

    private const val PERCENT_FRAGMENT = """(\d{1,3}(?:\.\d{1,2})?)\s*%"""
    private val PERCENT_REGEX = Regex("\\b$PERCENT_FRAGMENT")

    private const val OTP_FRAGMENT = """(\d{4,8})"""
    private val OTP_DIGITS_REGEX = Regex("\\b\\d{4,8}\\b")
    private val OTP_CONTEXT_REGEX = Regex("(?i)\\b(?:otp|one[\\s-]?time|verification|verify|passcode|code)\\b")

    private const val VPA_FRAGMENT = """([A-Za-z0-9._-]+@[A-Za-z]+)"""
    private val VPA_REGEX = Regex("""\b[A-Za-z0-9._-]{2,64}@[A-Za-z]{2,32}\b""")

    private val GENERIC_NUMBER_REGEX = Regex("""\d+(?:,\d+)*(?:\.\d+)?""")

    private val POST_PREPOSITION_REGEX =
        Regex("""\b(?i:to|at|towards|from)\b\s+([A-Z][A-Za-z0-9&._-]{1,29}(?:\s[A-Z][A-Za-z0-9&._-]{1,29}){0,2})""")
    private val ALL_CAPS_WORD_REGEX = Regex("""\b[A-Z][A-Z0-9&]{2,19}\b""")
    private val TITLE_CASE_WORD_REGEX = Regex("""\b[A-Z][a-z]{2,19}\b""")

    private const val MAX_VENDOR_CANDIDATES = 5

    private val KNOWN_BRANDS =
        setOf(
            "amazon",
            "flipkart",
            "myntra",
            "swiggy",
            "zomato",
            "uber",
            "ola",
            "paytm",
            "phonepe",
            "gpay",
            "bigbasket",
            "blinkit",
            "zepto",
            "meesho",
            "nykaa",
            "irctc",
            "netflix",
            "hotstar",
            "jio",
            "airtel",
            "vodafone",
            "bsnl",
            "dominos",
            "makemytrip",
            "rapido",
        )

    /** Words too generic to be useful vendor candidates. */
    private val VENDOR_STOPWORDS =
        setOf(
            "dear",
            "customer",
            "your",
            "you",
            "the",
            "for",
            "from",
            "with",
            "and",
            "not",
            "avl",
            "bal",
            "balance",
            "available",
            "info",
            "alert",
            "bank",
            "card",
            "account",
            "otp",
            "upi",
            "inr",
            "sms",
            "call",
            "please",
            "thank",
            "thanks",
            "valid",
            "mins",
            "min",
            "use",
            "code",
            "ref",
            "txn",
            "neft",
            "imps",
            "rtgs",
            "credit",
            "debit",
            "credited",
            "debited",
            "wallet",
            "total",
            "due",
            "bill",
            "order",
            "delivered",
            "payment",
            "share",
            "anyone",
            "login",
            "netbanking",
            "towards",
            "sent",
            "received",
            "vpa",
            "pay",
            "using",
        )

    /** Curated high-signal words worth offering as `body_must_contain` toggles. */
    private val CURATED_KEYWORDS =
        listOf(
            "debited",
            "credited",
            "delivered",
            "due",
            "withdrawn",
            "spent",
            "received",
            "refund",
            "refunded",
            "recharge",
            "recharged",
            "bill",
            "payment",
            "paid",
            "balance",
            "otp",
            "order",
            "shipped",
            "dispatched",
            "declined",
            "failed",
            "insurance",
            "premium",
            "statement",
            "overdue",
            "expires",
            "emi",
        )

    /**
     * Detects all candidate tokens in [body], sorted by position. Numeric spans
     * are claimed by at most one token (most specific kind wins) so every digit
     * group is reported exactly once - either as a typed token or GENERIC_NUMBER.
     */
    fun suggest(body: String): List<SuggestedToken> {
        val claimed = mutableListOf<IntRange>()
        val tokens = mutableListOf<SuggestedToken>()

        fun tryClaim(token: SuggestedToken): Boolean {
            val range = token.start until token.end
            if (claimed.any { it.first < range.last + 1 && range.first < it.last + 1 }) return false
            claimed += range
            tokens += token
            return true
        }

        BALANCE_REGEX.findAll(body).forEach { m ->
            tryClaim(token(TokenKind.BALANCE, m, BALANCE_FRAGMENT, Fields.BALANCE))
        }
        AMOUNT_REGEX.findAll(body).forEach { m ->
            tryClaim(token(TokenKind.AMOUNT, m, AMOUNT_FRAGMENT, Fields.AMOUNT))
        }
        ACCOUNT_REGEX.findAll(body).forEach { m ->
            tryClaim(token(TokenKind.ACCOUNT_LAST4, m, ACCOUNT_FRAGMENT, Fields.ACCOUNT_LAST4))
        }
        DATE_MONTH_REGEX.findAll(body).forEach { m ->
            tryClaim(dateToken(body, m, DATE_MONTH_FRAGMENT))
        }
        DATE_NUMERIC_REGEX.findAll(body).forEach { m ->
            tryClaim(dateToken(body, m, DATE_NUMERIC_FRAGMENT))
        }
        REFERENCE_REGEX.findAll(body).forEach { m ->
            tryClaim(token(TokenKind.REFERENCE, m, REFERENCE_FRAGMENT, Fields.REFERENCE))
        }
        detectVendors(body).forEach(::tryClaim)
        detectOtp(body, claimed)?.let(::tryClaim)
        PERCENT_REGEX.findAll(body).forEach { m ->
            tryClaim(token(TokenKind.PERCENT, m, PERCENT_FRAGMENT, suggestedField = null))
        }
        GENERIC_NUMBER_REGEX.findAll(body).forEach { m ->
            tryClaim(
                SuggestedToken(
                    kind = TokenKind.GENERIC_NUMBER,
                    literal = m.value,
                    start = m.range.first,
                    end = m.range.last + 1,
                    captureFragment = genericNumberFragment(m.value),
                ),
            )
        }
        tokens += detectKeywords(body)
        return tokens.sortedBy { it.start }
    }

    /**
     * Sender pattern derived from the actual sender: TRAI operator route prefix
     * (`XY-`) and suffix (`-S`) stripped, regex metacharacters escaped, matched
     * case-insensitively. E.g. `VM-HDFCBK-S` → `(?i)HDFCBK`.
     */
    fun senderPattern(sender: String): String {
        val core =
            sender
                .trim()
                .uppercase()
                .replace(Regex("^[A-Z]{2}-"), "")
                .replace(Regex("-[SPTGE]$"), "")
        return "(?i)" + RuleComposer.escapeLiteral(core)
    }

    private fun token(
        kind: TokenKind,
        match: MatchResult,
        fragment: String,
        suggestedField: String?,
    ): SuggestedToken =
        SuggestedToken(
            kind = kind,
            literal = match.groupValues.getOrElse(1) { match.value }.ifEmpty { match.value },
            start = match.range.first,
            end = match.range.last + 1,
            captureFragment = fragment,
            suggestedField = suggestedField,
        )

    private fun dateToken(
        body: String,
        match: MatchResult,
        fragment: String,
    ): SuggestedToken =
        token(
            kind = TokenKind.DATE,
            match = match,
            fragment = fragment,
            suggestedField = if (body.contains("due", ignoreCase = true)) Fields.DUE_DATE else null,
        )

    /** Picks the 4-8 digit run nearest an OTP/verification context word, if any. */
    private fun detectOtp(
        body: String,
        claimed: List<IntRange>,
    ): SuggestedToken? {
        val contexts = OTP_CONTEXT_REGEX.findAll(body).map { it.range.first }.toList()
        if (contexts.isEmpty()) return null
        val candidate =
            OTP_DIGITS_REGEX
                .findAll(body)
                .filter { m -> claimed.none { it.first <= m.range.last && m.range.first <= it.last } }
                .minByOrNull { m -> contexts.minOf { ctx -> kotlin.math.abs(ctx - m.range.first) } }
                ?: return null
        return SuggestedToken(
            kind = TokenKind.OTP_CODE,
            literal = candidate.value,
            start = candidate.range.first,
            end = candidate.range.last + 1,
            captureFragment = OTP_FRAGMENT,
            suggestedField = Fields.OTP_CODE,
        )
    }

    /**
     * Vendor/merchant candidates, ranked: UPI VPA handles and names right after
     * `to|at|towards|from` beat known brands, which beat plain capitalized words.
     * Deduplicated case-insensitively and capped at [MAX_VENDOR_CANDIDATES].
     */
    private fun detectVendors(body: String): List<SuggestedToken> {
        data class Candidate(
            val range: IntRange,
            val literal: String,
            val score: Int,
        )

        val candidates = mutableListOf<Candidate>()
        VPA_REGEX.findAll(body).forEach { candidates += Candidate(it.range, it.value, score = 100) }
        POST_PREPOSITION_REGEX.findAll(body).forEach { m ->
            val group = m.groups[1] ?: return@forEach
            if (!isVendorStopword(group.value)) candidates += Candidate(group.range, group.value, score = 90)
        }
        ALL_CAPS_WORD_REGEX.findAll(body).forEach { m ->
            val score = if (m.value.lowercase() in KNOWN_BRANDS) 80 else 40
            if (!isVendorStopword(m.value)) candidates += Candidate(m.range, m.value, score)
        }
        TITLE_CASE_WORD_REGEX.findAll(body).forEach { m ->
            val score = if (m.value.lowercase() in KNOWN_BRANDS) 80 else 30
            if (!isVendorStopword(m.value)) candidates += Candidate(m.range, m.value, score)
        }
        return candidates
            .sortedWith(compareByDescending<Candidate> { it.score }.thenBy { it.range.first })
            .distinctBy { it.literal.lowercase() }
            .take(MAX_VENDOR_CANDIDATES)
            .mapIndexed { index, c ->
                SuggestedToken(
                    kind = TokenKind.VENDOR,
                    literal = c.literal,
                    start = c.range.first,
                    end = c.range.last + 1,
                    captureFragment = vendorFragment(c.literal),
                    suggestedField = Fields.MERCHANT,
                    rank = index,
                )
            }
    }

    private fun isVendorStopword(candidate: String): Boolean = candidate.split(' ').firstOrNull()?.lowercase() in VENDOR_STOPWORDS

    private fun vendorFragment(literal: String): String =
        when {
            literal.contains('@') -> VPA_FRAGMENT
            literal.contains(' ') -> """([A-Za-z0-9&@._\- ]{2,40})"""
            else -> """([A-Za-z0-9&@._-]{2,30})"""
        }

    /** Bounded digit-group fragment sized from the literal, e.g. "1234" → `(\d{2,6})`. */
    private fun genericNumberFragment(literal: String): String {
        if (literal.any { it == ',' || it == '.' }) return """([\d,]+(?:\.\d{1,2})?)"""
        val len = literal.length
        val lo = maxOf(1, len - 2)
        val hi = len + 2
        return "(\\d{$lo,$hi})"
    }

    private fun detectKeywords(body: String): List<SuggestedToken> =
        CURATED_KEYWORDS.mapNotNull { word ->
            val match = Regex("(?i)\\b${Regex.escape(word)}\\b").find(body) ?: return@mapNotNull null
            SuggestedToken(
                kind = TokenKind.KEYWORD,
                literal = word,
                start = match.range.first,
                end = match.range.last + 1,
                captureFragment = RuleComposer.escapeLiteral(word),
            )
        }
}
