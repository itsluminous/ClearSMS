package app.clearsms.domain.rules

import java.util.regex.Pattern

/** A token the user chose to capture, mapped onto an extract field key. */
data class CapturePick(
    val token: SuggestedToken,
    val field: String,
)

/**
 * Result of composing a body pattern: the pattern plus the `$N` extract mapping
 * whose group numbers follow the capture order inside the pattern.
 */
data class ComposedBody(
    val bodyPattern: String,
    val extract: Map<String, String>,
)

/**
 * Composes safe rule regexes from a concrete message plus the user's token picks.
 *
 * Safety invariants (see the ReDoS regression tests around the rule engine):
 * - literal context is regex-escaped; whitespace runs become `\s+`, digit runs
 *   become bounded `\d{n,m}` groups,
 * - NEVER emits a leading/trailing `.*` / `[\s\S]*` catch-all wrapper — the
 *   engine uses `find()` so wrappers only add catastrophic backtracking risk,
 * - never emits unbounded nested quantifiers.
 */
object RuleComposer {
    private val REGEX_SPECIALS = setOf('\\', '.', '[', ']', '{', '}', '(', ')', '*', '+', '-', '?', '^', '$', '|')
    private val DIGIT_RUN = Regex("""\d+(?:,\d+)*(?:\.\d+)?""")
    private val LEADING_INLINE_FLAGS = Regex("""^\(\?[a-zA-Z]+\)""")
    private val CATCH_ALL_EDGES = listOf(".*", ".+", "[\\s\\S]*", "[\\s\\S]+", "[\\w\\W]*", "[\\w\\W]+")

    /**
     * Builds the body pattern by escaping the literal context around the picked
     * tokens and substituting each picked span with its capture fragment. The
     * returned extract map references groups in span order (`$1`, `$2`, ...).
     *
     * The whole pattern is case-insensitive so it survives casing variations in
     * sibling messages.
     */
    fun composeBody(
        body: String,
        picks: List<CapturePick>,
    ): ComposedBody {
        val ordered = picks.sortedBy { it.token.start }
        val sb = StringBuilder("(?i)")
        val extract = LinkedHashMap<String, String>()
        var pos = 0
        var group = 0
        for (pick in ordered) {
            val token = pick.token
            if (token.start < pos) continue // defensively skip overlapping picks
            sb.append(generalizeLiteral(body.substring(pos, token.start)))
            sb.append(token.captureFragment)
            group++
            extract[pick.field] = "$$group"
            pos = token.end
        }
        sb.append(generalizeLiteral(body.substring(pos)))
        return ComposedBody(sb.toString(), extract)
    }

    /**
     * Escapes literal message text into a safe pattern fragment: metacharacters
     * escaped, whitespace runs generalized to `\s+`, digit runs generalized to
     * bounded `\d{n,m}` (comma/decimal amounts to `[\d,]+(?:\.\d{1,2})?`).
     */
    fun generalizeLiteral(text: String): String {
        val out = StringBuilder()
        var i = 0
        while (i < text.length) {
            val c = text[i]
            when {
                c.isDigit() -> {
                    val run = DIGIT_RUN.matchAt(text, i)
                    if (run != null) {
                        out.append(digitRunFragment(run.value))
                        i = run.range.last + 1
                        continue
                    }
                    out.append(c)
                }
                c.isWhitespace() -> {
                    while (i < text.length && text[i].isWhitespace()) i++
                    out.append("\\s+")
                    continue
                }
                c in REGEX_SPECIALS -> out.append('\\').append(c)
                else -> out.append(c)
            }
            i++
        }
        return out.toString()
    }

    /** Escapes every regex metacharacter in [text] with no generalization. */
    fun escapeLiteral(text: String): String =
        buildString {
            for (c in text) {
                if (c in REGEX_SPECIALS) append('\\')
                append(c)
            }
        }

    /**
     * True when the pattern starts or ends with a catch-all wrapper such as
     * `.*` or `[\s\S]*`. The engine already scans with `find()`, so wrappers
     * add nothing but (measured) catastrophic-backtracking risk.
     */
    fun hasCatchAllWrapper(pattern: String): Boolean {
        val trimmed = pattern.trim().replace(LEADING_INLINE_FLAGS, "")
        if (CATCH_ALL_EDGES.any { trimmed.startsWith(it) }) return true
        return CATCH_ALL_EDGES.any { edge ->
            trimmed.endsWith(edge) && !trimmed.endsWith("\\" + edge)
        }
    }

    /** Number of capturing groups in [pattern]; -1 when the pattern is invalid. */
    fun captureGroupCount(pattern: String): Int =
        try {
            Pattern.compile(pattern).matcher("").groupCount()
        } catch (_: Exception) {
            -1
        }

    /** Highest `$N` group reference used in [extract] values (0 when none). */
    fun maxGroupReference(extract: Map<String, String>): Int =
        extract.values
            .flatMap { value -> Regex("""\$(\d+)""").findAll(value).map { it.groupValues[1].toInt() } }
            .maxOrNull() ?: 0

    private fun digitRunFragment(run: String): String {
        if (run.any { it == ',' || it == '.' }) return """[\d,]+(?:\.\d{1,2})?"""
        val len = run.length
        val lo = maxOf(1, len - 2)
        val hi = len + 2
        return "\\d{$lo,$hi}"
    }
}
