package app.clearsms.ui.components

import androidx.core.util.PatternsCompat

/** What a link does when tapped - the tap target's copy and risk differ. */
enum class BodyLinkKind {
    WEB,
    EMAIL,
    PHONE,
    PAYMENT,
}

/**
 * A link found in a message body: where it sits in the text, and the URI to
 * hand to another app.
 */
data class BodyLink(
    val start: Int,
    val end: Int,
    /** The text as written in the message. */
    val text: String,
    /** The resolved target - always carries a scheme. */
    val url: String,
    val kind: BodyLinkKind = BodyLinkKind.WEB,
)

/**
 * Finds the tappable things in a message body: web addresses, email
 * addresses, payment links and phone numbers.
 *
 * The traps matter more than the hits. An SMS inbox is mostly digit groups,
 * and an Indian PNR is TEN digits - indistinguishable in shape from a mobile
 * number - so phone detection is gated three ways:
 *
 * 1. shape: `+CC` followed by 7-14 digits, or a bare 10-digit number starting
 *    6-9 (the Indian mobile range). Separators (spaces/hyphens) are allowed
 *    inside, but the run must not touch more digits on either side, which
 *    keeps card numbers, account numbers and 11-digit transaction ids out;
 * 2. context: a preceding reference word - PNR, ref, txn, order, UTR, SR,
 *    folio, invoice, policy, a/c, card, consumer, ticket, AWB, docket - vetoes
 *    the match, because those identifiers are not for dialling;
 * 3. currency: a preceding Rs/INR/currency symbol vetoes it too.
 *
 * Short helpline codes (139, 1930, 112) are NOT matched: three and four digit
 * runs are everywhere in this corpus - amounts, years, quantities - and a
 * dialer opening on "Rs 200" would be worse than having to type 139.
 *
 * [PatternsCompat.WEB_URL] supplies web matches (it insists on a real
 * top-level domain, which is what keeps `Rs.4210.5` and `13-09-26` out), and
 * a scheme-less host is opened over https.
 */
object BodyLinkFinder {
    private val schemeRegex = Regex("^[a-zA-Z][a-zA-Z0-9+.-]*:")

    /** Payment and dialer URIs written out in full by the sender. */
    private val appSchemeRegex =
        Regex(
            "(?i)\\b(?:upi|tel|sms):(?://)?[^\\s<>\"]{2,120}",
        )

    /**
     * A candidate digit run: optional `+`, then digits with optional single
     * spaces or hyphens between them. Deliberately loose - [dialableNumber]
     * decides what actually counts, which is far easier to reason about than
     * one regex trying to express every grouping style ("+91 98765 43210",
     * "98765-43210", "9876543210").
     */
    private val phoneCandidateRegex = Regex("(?<![\\d])\\+?\\d(?:[\\s-]?\\d){6,17}(?![\\d])")

    /** Words that make a digit run an identifier rather than a number to dial. */
    private val referenceContext =
        Regex(
            "(?i)(?:PNR|ref(?:erence)?|txn|transaction|order|UTR|RRN|IMPS|NEFT|SR|folio|invoice|" +
                "policy|a/c|acc(?:t|ount)?|card|consumer|ticket|AWB|docket|tracking|GST|ID|no\\.?|number)" +
                "[\\s:#-]*$",
        )

    /** Currency immediately before a digit run makes it an amount. */
    private val currencyContext = Regex("(?i)(?:INR|Rs\\.?|\\u20b9)\\s*$")

    fun find(body: String): List<BodyLink> {
        if (body.isBlank()) return emptyList()
        val found = ArrayList<BodyLink>()

        appSchemeRegex.findAll(body).forEach { match ->
            val trimmed = match.value.trimEnd('.', ',', ';', ')', ']', '"', '\'')
            val scheme = trimmed.substringBefore(':').lowercase()
            found +=
                BodyLink(
                    start = match.range.first,
                    end = match.range.first + trimmed.length,
                    text = trimmed,
                    url = trimmed,
                    kind = if (scheme == "upi") BodyLinkKind.PAYMENT else BodyLinkKind.PHONE,
                )
        }

        PatternsCompat.WEB_URL.matcher(body).let { matcher ->
            while (matcher.find()) {
                val trimmed = matcher.group().trimEnd('.', ',', ';', ':', ')', ']', '"', '\'')
                if (trimmed.isEmpty()) continue
                found +=
                    BodyLink(
                        start = matcher.start(),
                        end = matcher.start() + trimmed.length,
                        text = trimmed,
                        url = if (schemeRegex.containsMatchIn(trimmed)) trimmed else "https://$trimmed",
                        kind = BodyLinkKind.WEB,
                    )
            }
        }

        PatternsCompat.EMAIL_ADDRESS.matcher(body).let { matcher ->
            while (matcher.find()) {
                val raw = matcher.group()
                found +=
                    BodyLink(
                        start = matcher.start(),
                        end = matcher.end(),
                        text = raw,
                        url = "mailto:$raw",
                        kind = BodyLinkKind.EMAIL,
                    )
            }
        }

        phoneCandidateRegex.findAll(body).forEach { match ->
            val before = body.substring(0, match.range.first)
            if (referenceContext.containsMatchIn(before)) return@forEach
            if (currencyContext.containsMatchIn(before)) return@forEach
            val dialable = dialableNumber(match.value) ?: return@forEach
            found +=
                BodyLink(
                    start = match.range.first,
                    end = match.range.last + 1,
                    text = match.value,
                    url = "tel:$dialable",
                    kind = BodyLinkKind.PHONE,
                )
        }

        // Earliest first, longest first on ties, then drop anything that
        // overlaps a link already kept - a phone-shaped run inside a URL path
        // must not become a second, competing link.
        val ordered = found.sortedWith(compareBy({ it.start }, { -(it.end - it.start) }))
        val kept = ArrayList<BodyLink>(ordered.size)
        for (link in ordered) {
            if (kept.none { link.start < it.end && it.start < link.end }) kept += link
        }
        return kept
    }

    /**
     * The dialable form of a candidate run, or null when it is not a number a
     * person would call:
     *
     * - with a country code: 8-15 digits (the E.164 range);
     * - a bare Indian mobile: exactly 10 digits starting 6-9;
     * - a bare toll-free line: 11 digits starting 1800.
     *
     * Everything else - 11-digit transaction ids, 16-digit cards, 6-digit
     * dates, PINs - is left as text. The 10-digit PNR case is caught by the
     * reference-word check at the call site, since its SHAPE is legitimate.
     */
    private fun dialableNumber(candidate: String): String? {
        val compact = candidate.filter { !it.isWhitespace() && it != '-' }
        val digits = compact.removePrefix("+")
        if (digits.any { !it.isDigit() }) return null
        return when {
            compact.startsWith("+") && digits.length in 8..15 -> compact
            digits.length == 10 && digits.first() in '6'..'9' -> digits
            digits.length == 11 && digits.startsWith("1800") -> digits
            else -> null
        }
    }
}
