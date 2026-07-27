package app.clearsms.ui.rules

/**
 * Suggests a body regex from a concrete message: literal text is escaped and
 * digit runs are generalized to `\d+` so the rule matches sibling messages
 * (different amounts, OTPs, account digits).
 */
object RegexSuggester {
    private val SPECIALS = setOf('\\', '.', '[', ']', '{', '}', '(', ')', '*', '+', '-', '?', '^', '$', '|')

    fun suggestBodyPattern(body: String): String {
        val out = StringBuilder()
        var i = 0
        while (i < body.length) {
            val c = body[i]
            when {
                c.isDigit() -> {
                    while (i < body.length && body[i].isDigit()) i++
                    out.append("\\d+")
                    continue
                }
                c.isWhitespace() -> {
                    while (i < body.length && body[i].isWhitespace()) i++
                    out.append("\\s+")
                    continue
                }
                c in SPECIALS -> out.append('\\').append(c)
                else -> out.append(c)
            }
            i++
        }
        return out.toString()
    }

    /** Sender pattern: escaped exact sender, anchored, tolerant of TRAI route prefixes/suffixes. */
    fun suggestSenderPattern(sender: String): String {
        val core =
            sender
                .trim()
                .uppercase()
                .replace(Regex("^[A-Z]{2}-"), "")
                .replace(Regex("-[SPTG]$"), "")
        val escaped = core.map { if (it in SPECIALS) "\\$it" else it.toString() }.joinToString("")
        return "(?i)$escaped"
    }
}
