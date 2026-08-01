package app.clearsms.domain.parser

import app.clearsms.domain.model.ParsedOtp

/**
 * Extracts one-time passwords from message bodies.
 *
 * Keyword-anchored patterns are tried first; a bare 6-digit number is accepted
 * only when the message contains a verification-context keyword, to avoid
 * treating amounts, ticket numbers etc. as OTPs.
 */
class OtpParser {
    fun parse(body: String): ParsedOtp? {
        parseAnchored(body)?.let { return it }
        if (VERIFICATION_CONTEXT.containsMatchIn(body)) {
            val bare = BARE_SIX_DIGITS.find(body)
            if (bare != null) return ParsedOtp(code = bare.groupValues[1])
        }
        return null
    }

    /**
     * Keyword-ANCHORED extraction only: the code must sit directly against an
     * OTP/code/PIN keyword ("OTP is 482910", "413423 is SECRET OTP"). Unlike
     * [parse] this never falls back to a bare six-digit number near a context
     * word, so a transaction reference in a debit alert that merely mentions
     * "OTP"/"PIN" in an advisory can never be mistaken for a code. Used where
     * a false OTP is costly — e.g. reclassifying a rule-matched transaction.
     */
    fun parseAnchored(body: String): ParsedOtp? {
        for (pattern in KEYWORD_PATTERNS) {
            val match = pattern.find(body)
            if (match != null) return ParsedOtp(code = match.groupValues[1])
        }
        return null
    }

    private companion object {
        /** e.g. "OTP is 482910", "code: 4821", "413423 is SECRET OTP". */
        val KEYWORD_PATTERNS =
            listOf(
                Regex("(?i)(?:otp|verification\\s+code|security\\s+code|code|password|pin)\\s*(?:is|:)?\\s*(\\d{4,8})(?!\\d)"),
                // "your"/"the" optional with up to three brand/adjective words
                // ("413423 is SECRET OTP", "482910 is your HDFC Bank OTP").
                Regex(
                    "(?i)(?<!\\d)(\\d{4,8})\\s+is\\s+(?:(?:your|the)\\s+)?(?:\\w+[ .-]){0,3}?" +
                        "(?:otp|one[\\s-]?time|verification|code|password|pin)\\b",
                ),
                Regex("(?i)(?:use|enter)\\s+(?:otp\\s+)?(\\d{4,8})\\s+(?:to|for|as)"),
            )

        val VERIFICATION_CONTEXT =
            Regex("(?i)\\b(?:otp|verif\\w*|authenticat\\w*|one[\\s-]?time|login|log[\\s-]?in|sign[\\s-]?in|code|password|pin)\\b")

        val BARE_SIX_DIGITS = Regex("(?<!\\d)(\\d{6})(?!\\d)")
    }
}
