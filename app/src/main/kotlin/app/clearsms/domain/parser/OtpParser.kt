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
        for (pattern in KEYWORD_PATTERNS) {
            val match = pattern.find(body)
            if (match != null) return ParsedOtp(code = match.groupValues[1])
        }
        if (VERIFICATION_CONTEXT.containsMatchIn(body)) {
            val bare = BARE_SIX_DIGITS.find(body)
            if (bare != null) return ParsedOtp(code = bare.groupValues[1])
        }
        return null
    }

    private companion object {
        /** e.g. "OTP is 482910", "code: 4821", "Your PIN 552211". */
        val KEYWORD_PATTERNS =
            listOf(
                Regex("(?i)(?:otp|verification\\s+code|security\\s+code|code|password|pin)\\s*(?:is|:)?\\s*(\\d{4,8})(?!\\d)"),
                Regex("(?i)(?<!\\d)(\\d{4,8})\\s+is\\s+(?:your|the)\\s+(?:otp|one[\\s-]?time|verification|code|password|pin)"),
                Regex("(?i)(?:use|enter)\\s+(?:otp\\s+)?(\\d{4,8})\\s+(?:to|for|as)"),
            )

        val VERIFICATION_CONTEXT =
            Regex("(?i)\\b(?:otp|verif\\w*|authenticat\\w*|one[\\s-]?time|login|log[\\s-]?in|sign[\\s-]?in|code|password|pin)\\b")

        val BARE_SIX_DIGITS = Regex("(?<!\\d)(\\d{6})(?!\\d)")
    }
}
