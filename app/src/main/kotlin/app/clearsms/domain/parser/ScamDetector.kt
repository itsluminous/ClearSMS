package app.clearsms.domain.parser

/**
 * Heuristic detector for likely scam / phishing SMS.
 *
 * Flags messages that combine a link (especially a URL-shortener domain) with
 * bait: prize/lottery language, urgency around KYC or account suspension, etc.
 * Strong prize-bait phrasing is flagged even without a link.
 */
class ScamDetector {
    fun isScam(body: String): Boolean {
        val hasShortener = SHORTENER_REGEX.containsMatchIn(body)
        val hasAnyLink = hasShortener || LINK_REGEX.containsMatchIn(body)
        val hasPrizeBait = PRIZE_REGEX.containsMatchIn(body)
        val hasUrgency = URGENCY_REGEX.containsMatchIn(body)
        val hasKyc = KYC_REGEX.containsMatchIn(body)

        return when {
            hasShortener && (hasPrizeBait || hasUrgency || hasKyc) -> true
            hasAnyLink && hasKyc && hasUrgency -> true
            hasPrizeBait && WINNER_CLAIM_REGEX.containsMatchIn(body) -> true
            else -> false
        }
    }

    private companion object {
        val SHORTENER_REGEX =
            Regex("(?i)\\b(?:bit\\.ly|tinyurl\\.com|t\\.co|goo\\.gl|cutt\\.ly|rb\\.gy|is\\.gd|tiny\\.cc|shorturl\\.at|ow\\.ly)\\b")

        val LINK_REGEX = Regex("(?i)\\bhttps?://|\\bwww\\.")

        val PRIZE_REGEX =
            Regex("(?i)\\b(?:prize|lottery|lucky\\s+draw|jackpot|winner|you\\s+have\\s+won|won\\s+(?:a|an|rs|inr|\\u20b9))\\b")

        val URGENCY_REGEX =
            Regex(
                "(?i)\\b(?:urgent(?:ly)?|immediately|within\\s+24\\s*(?:hrs|hours)|today\\s+itself|expir\\w*|suspend\\w*|block\\w*|deactivat\\w*)\\b",
            )

        val KYC_REGEX = Regex("(?i)\\bKYC\\b|know\\s+your\\s+customer|pan\\s+card\\s+update|aadhaa?r\\s+update")

        val WINNER_CLAIM_REGEX = Regex("(?i)claim\\s+(?:your|now|prize|reward|gift)|call\\s+(?:now|immediately)")
    }
}
