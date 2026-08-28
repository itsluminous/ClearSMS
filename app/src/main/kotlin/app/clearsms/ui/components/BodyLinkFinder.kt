package app.clearsms.ui.components

import androidx.core.util.PatternsCompat

/**
 * A link found in a message body: where it sits in the text, and the URL to
 * hand to another app.
 */
data class BodyLink(
    val start: Int,
    val end: Int,
    /** The text as written in the message. */
    val text: String,
    /** The resolved target - always carries a scheme. */
    val url: String,
)

/**
 * Finds the web links and email addresses in a message body so they can be
 * tapped. Deliberately conservative about what counts as a link, because SMS
 * is full of digit groups that must NOT become one:
 *
 * - web matches come from [PatternsCompat.WEB_URL], which requires a real
 *   top-level domain. That is what lets `porter.in/rd/2ece6fcccd` (no
 *   scheme) be a link while `Rs.2878.8`, `13-09-26` and `A/c XX4321` are
 *   left alone;
 * - a scheme-less match is opened as `https://`, since a bare host in an SMS
 *   is a web address in practice;
 * - phone numbers are NOT linked. Bodies are dense with amounts, PNRs,
 *   account tails and reference numbers, and Android's phone matcher claims
 *   many of them - a wrong tel: link is worse than no link;
 * - overlapping matches keep the FIRST (longest-leading) one, so an email
 *   inside a URL-ish string cannot produce two overlapping links;
 * - only web and email links are produced. App-scheme links - `upi://` above
 *   all - are left as plain text on purpose: a tap that opens a payment app
 *   with an attacker's amount pre-filled is the exact flow SMS scams use, and
 *   payment requests already surface here as unsigned notices rather than
 *   actions.
 */
object BodyLinkFinder {
    private val schemeRegex = Regex("^[a-zA-Z][a-zA-Z0-9+.-]*:")

    fun find(body: String): List<BodyLink> {
        if (body.isBlank()) return emptyList()
        val found = ArrayList<BodyLink>()

        PatternsCompat.WEB_URL.matcher(body).let { matcher ->
            while (matcher.find()) {
                val raw = matcher.group()
                // Trailing punctuation belongs to the sentence, not the link.
                val trimmed = raw.trimEnd('.', ',', ';', ':', ')', ']', '"', '\'')
                if (trimmed.isEmpty()) continue
                found +=
                    BodyLink(
                        start = matcher.start(),
                        end = matcher.start() + trimmed.length,
                        text = trimmed,
                        url = if (schemeRegex.containsMatchIn(trimmed)) trimmed else "https://$trimmed",
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
                    )
            }
        }

        // Earliest first, longest first on ties, then drop anything that
        // overlaps a link already kept.
        val ordered = found.sortedWith(compareBy({ it.start }, { -(it.end - it.start) }))
        val kept = ArrayList<BodyLink>(ordered.size)
        for (link in ordered) {
            if (kept.none { link.start < it.end && it.start < link.end }) kept += link
        }
        return kept
    }
}
