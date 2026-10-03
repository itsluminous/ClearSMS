package app.clearsms

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import org.junit.Test
import java.io.File

/**
 * User-visible labels come from string resources, never from literals in
 * code - otherwise a translation can be 100% complete and the UI still show
 * English, which is exactly the bug this guards against: the inbox filter
 * pills stayed "Important", "Promotional" … in Hindi because
 * `Category.displayName()` was a `when` returning literals.
 *
 * An audit that grepped for `Text("...")` missed that whole class, because
 * the literal is a `when` BRANCH result, not a `Text` argument. So this test
 * looks for the shape itself, in every main source file:
 *
 * 1. a `when` branch whose result is a bare string literal that reads as a
 *    human word or phrase (`Category.FOO -> "Foo"`, `nowDate -> "Today"`,
 *    `else -> "Bank alert"`), and
 * 2. a label derived from an enum NAME or a JSON key (`name.lowercase()
 *    .replace('_', ' ')`, `key.replace('_', ' ').replaceFirstChar { … }`),
 *    which is unlocalizable by construction,
 * 3. a bare literal handed straight to `Text("…")`, whatever its case, and
 * 4. a STORED id shown as its own label: a list of lower-case ids
 *    (`listOf("important", "bank_alert", …)`) whose loop variable reaches a
 *    `Text(option)`. This is the hole the first version of this test had -
 *    it only judged `when` branches, and only capitalised ones, so the rule
 *    wizard's chips reading `important` / `bank_alert` and the detail card's
 *    humanised `account_last4` → "Account last4:" both slipped through.
 *
 * It stays narrow on purpose: `when` branch results that are codes, MIME
 * types, paths, keys, interpolations or lower-case identifiers (`-> "debit"`
 * is a serialized value, not a label) are ignored, and the diagnostics
 * package is skipped because the shareable report is deliberately English
 * (see [DiagnosticLogConventionTest]). Verified against planted literals in
 * the self-test below, against the real `CategoryBadge.kt` before the BO1
 * fix, and against `RuleWizardScreen.kt` / `ConversationScreen.kt` before
 * the BO3 fix.
 */
class UserVisibleLabelConventionTest {
    private data class Finding(
        val file: String,
        val line: Int,
        val text: String,
    ) {
        override fun toString() = "$file:$line  ${text.trim()}"
    }

    /**
     * `<condition> -> "<literal>"` on one line, optionally with a trailing
     * comma. The condition may not itself start with a quote: `"amount" ->
     * "..."` is a key-to-code map, not a label for an enum constant.
     */
    private val branchLiteral = Regex("""^\s*([^"\s][^"]*?)\s*->\s*"((?:\\.|[^"\\])*)"\s*,?\s*$""")

    /** "Important", "Bank alert", "Not sent", "Today" - a capitalised word or phrase. */
    private val titleCasePhrase = Regex("""^[A-Z][a-z]+(?: [a-z]+)*[.!?…]?$""")

    /** "OTP", "EMI" - an all-caps word. Only a label when it is the result for an enum constant. */
    private val allCapsWord = Regex("""^[A-Z]{2,}$""")

    /** `Category.OTP`, `SubCategory.BANK_ALERT`, or a bare `OTP` inside the enum's own `when`. */
    private val enumConstant = Regex("""^(?:[A-Za-z_][\w.]*\.)?[A-Z][A-Z0-9_]*$""")

    /**
     * `name.lowercase().replace('_', ' ')`, `key.replace('_', ' ').replaceFirstChar { … }`
     * - humanising an enum name or a JSON key into a label: an underscore
     * swapped for a space TOGETHER with a case change on the same line.
     */
    private val underscoreToSpace = Regex("""\.replace\('_',\s*' '\)""")
    private val caseChange = Regex("""\.lowercase\(\)|\.uppercase\(\)|replaceFirstChar|uppercaseChar|\.capitalize\(""")

    /**
     * `Text("Important")`, `Text(text = "debit")` - a literal shown as-is.
     * Any letter makes it a word; `Text("•")` or `Text("₹")` are symbols.
     */
    private val bareTextLiteral = Regex("""\bText\(\s*(?:text\s*=\s*)?"((?:\\.|[^"\\])*)"""")
    private val hasLetter = Regex("""[A-Za-z]""")

    /** `val NAME = listOf("important", "bank_alert", …)` - a list of stored lower-case ids. */
    private val idList = Regex("""\bval\s+(\w+)\s*=\s*listOf\(((?:\s*"[a-z][a-z0-9_]*"\s*,?)+)\s*\)""")

    private fun sources(): Sequence<File> =
        File("src/main/kotlin")
            .walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            // The diagnostic report is developer-facing and English by design.
            .filterNot { it.path.contains("/diagnostics/") }

    private fun literalBranches(
        path: String,
        text: String,
    ): List<Finding> {
        val lines = text.lines()
        val findings = mutableListOf<Finding>()
        var index = 0
        while (index < lines.size) {
            var line = lines[index]
            val reported = index + 1
            // ktlint may wrap a long branch as `X ->` / `"literal"` on the
            // next line; judge the two as one.
            if (line.trimEnd().endsWith("->") && index + 1 < lines.size) {
                line = line.trimEnd() + " " + lines[index + 1].trim()
                index++
            }
            index++
            val match = branchLiteral.find(line) ?: continue
            val (condition, literal) = match.destructured
            // An interpolation is not a bare literal; the pieces it glues are
            // judged where they are declared.
            if ('$' in literal) continue
            val isLabel =
                titleCasePhrase.matches(literal) ||
                    (allCapsWord.matches(literal) && enumConstant.matches(condition))
            if (isLabel) findings += Finding(path, reported, line)
        }
        return findings
    }

    private fun enumNameLabels(
        path: String,
        text: String,
    ): List<Finding> =
        text.lines().mapIndexedNotNull { index, line ->
            if (underscoreToSpace.containsMatchIn(line) && caseChange.containsMatchIn(line)) {
                Finding(path, index + 1, line)
            } else {
                null
            }
        }

    private fun bareTextLiterals(
        path: String,
        text: String,
    ): List<Finding> =
        text.lines().mapIndexedNotNull { index, line ->
            val literal = bareTextLiteral.find(line)?.groupValues?.get(1) ?: return@mapIndexedNotNull null
            if ('$' in literal || !hasLetter.containsMatchIn(literal)) return@mapIndexedNotNull null
            Finding(path, index + 1, line)
        }

    /**
     * A stored id rendered as its own label: for every lower-case id list in
     * the file, the body of each `NAME.forEach { option -> … }` /
     * `for (option in NAME)` is searched for a `Text(option)` (also as the
     * `else` of an inline `if`, the chip-label idiom).
     */
    private fun rawIdLabels(
        path: String,
        text: String,
    ): List<Finding> {
        val findings = mutableListOf<Finding>()
        for (list in idList.findAll(text)) {
            val name = list.groupValues[1]
            val loops =
                Regex("""\b$name\s*\.forEach(?:Indexed)?\s*\{\s*(?:\w+\s*,\s*)?(\w+)\s*->""").findAll(text) +
                    Regex("""\bfor\s*\(\s*(\w+)\s+in\s+$name\s*\)\s*\{""").findAll(text)
            for (loop in loops) {
                val variable = loop.groupValues[1]
                val open = text.indexOf('{', loop.range.first)
                val body = text.substring(open, closingBrace(text, open))
                val shown = Regex("""\bText\(\s*(?:text\s*=\s*)?(?:if\s*\(.*?\)\s*.*?\s+else\s+)?$variable\s*[,)]""")
                for (hit in shown.findAll(body)) {
                    val line = text.substring(0, open + hit.range.first).count { it == '\n' } + 1
                    findings += Finding(path, line, text.lines()[line - 1])
                }
            }
        }
        return findings
    }

    /** Index just past the brace matching the one at [open] (or the end of [text]). */
    private fun closingBrace(
        text: String,
        open: Int,
    ): Int {
        var depth = 0
        for (i in open until text.length) {
            when (text[i]) {
                '{' -> depth++
                '}' -> if (--depth == 0) return i + 1
            }
        }
        return text.length
    }

    @Test
    fun `no when branch hands the user a literal word as a label`() {
        val findings = sources().flatMap { literalBranches(it.path, it.readText()) }.toList()
        assertWithMessage(
            "A label a user reads must be a string resource (R.string / stringResource), " +
                "with its translation in values-hi/, never a literal returned from a when branch:\n" +
                findings.joinToString("\n"),
        ).that(findings).isEmpty()
    }

    @Test
    fun `no label is manufactured from an enum name or a key`() {
        val findings = sources().flatMap { enumNameLabels(it.path, it.readText()) }.toList()
        assertWithMessage(
            "Enum names and JSON keys are STORED values; a label for one is a string resource, " +
                "never name.lowercase().replace('_', ' ') or key.replace('_', ' ').replaceFirstChar { … }:\n" +
                findings.joinToString("\n"),
        ).that(findings).isEmpty()
    }

    @Test
    fun `no Text shows a bare literal`() {
        val findings = sources().flatMap { bareTextLiterals(it.path, it.readText()) }.toList()
        assertWithMessage(
            "A word shown with Text(\"…\") is a string resource (stringResource(R.string.…)), " +
                "whatever its case:\n" + findings.joinToString("\n"),
        ).that(findings).isEmpty()
    }

    @Test
    fun `no stored id is shown as its own label`() {
        val findings = sources().flatMap { rawIdLabels(it.path, it.readText()) }.toList()
        assertWithMessage(
            "A lower-case id (\"important\", \"bank_alert\") is the STORED value; what the user sees is " +
                "its string resource (e.g. RuleEngine.categoryOf(id).displayName()), never the id itself:\n" +
                findings.joinToString("\n"),
        ).that(findings).isEmpty()
    }

    @Test
    fun `the scanner itself catches the planted shapes and ignores codes`() {
        val planted =
            """
            fun Category.displayName(): String =
                when (this) {
                    Category.IMPORTANT -> "Important"
                    Category.OTP -> "OTP"
                    Category.SPAM -> R.string.category_spam
                    SubCategory.BANK_ALERT ->
                        "Bank alert"
                    else -> "Not sent"
                }
            return when (thenDate) {
                nowDate -> "Today"
                else -> fullDateFormat.format(thenDate)
            }
            val mime = when (code) {
                0x1E -> "image/jpeg"
                else -> "application/octet-stream"
            }
            val transport = when {
                caps.hasTransport(TRANSPORT_WIFI) -> "WIFI"
                else -> "OTHER"
            }
            val sql = when (type) {
                TransactionType.DEBIT -> "debit"
                "amount" -> "Amount"
                x -> "${'$'}a - ${'$'}b"
            }
            private fun categoryLabel(name: String): String = name.lowercase().replace('_', ' ').replaceFirstChar { it.uppercaseChar() }
            Text(text = key.replace('_', ' ').replaceFirstChar { it.uppercaseChar() } + ": ")
            val sql = type.name.lowercase().replace('_', '-')
            Text("Important")
            Text(text = "debit")
            Text("•")
            Text("${'$'}count")
            Text(stringResource(R.string.category_spam))
            private val CATEGORY_OPTIONS = listOf("important", "promotional", "otp")
            private val SUB_CATEGORY_OPTIONS =
                listOf(
                    "transaction",
                    "bank_alert",
                )
            private val HINTS = listOf("bank", "credit card")
            CATEGORY_OPTIONS.forEach { option ->
                FilterChip(label = { Text(option) })
            }
            SUB_CATEGORY_OPTIONS.forEach { option ->
                FilterChip(label = { Text(if (option == FIELD_IGNORE) stringResource(R.string.ignore) else option) })
            }
            for (hint in HINTS) {
                Text(hint)
            }
            CATEGORY_OPTIONS.forEach { option ->
                FilterChip(label = { Text(RuleEngine.categoryOf(option).displayName()) })
            }
            """.trimIndent()
        val branches = literalBranches("planted.kt", planted)
        // Two enum-constant labels (one of them the all-caps OTP), a wrapped
        // branch, an else-branch phrase and the relative-date word. NOT the
        // resource id, the MIME types, the diagnostic transport codes (all
        // caps, but behind a boolean / else condition), the lower-case SQL
        // value, the string-keyed map entry or the interpolation.
        assertThat(branches.map { it.line }).containsExactly(3, 4, 6, 8, 11).inOrder()
        // The enum-name humaniser AND the key humaniser (no lowercase() call,
        // which is what used to hide it); NOT the underscore-to-dash SQL name.
        assertThat(enumNameLabels("planted.kt", planted).map { it.line }).containsExactly(27, 28).inOrder()
        // A capitalised word and a lower-case one; NOT the symbol, the
        // interpolation or the resource.
        assertThat(bareTextLiterals("planted.kt", planted).map { it.line }).containsExactly(30, 31).inOrder()
        // The two chip rows showing their id (one as the else of an inline
        // if); NOT the hint list (its "credit card" is a phrase, not an id)
        // nor the row that maps the id to its resource.
        assertThat(rawIdLabels("planted.kt", planted).map { it.line }).containsExactly(43, 46).inOrder()
    }

    @Test
    fun `every category, sub-category and extract-key label resource exists in English and in Hindi`() {
        // The one-definition files: every R.string they name must resolve in
        // both languages, or a Hindi user gets English (or a crash) back.
        val labels =
            File("src/main/kotlin/app/clearsms/ui/components/CategoryLabels.kt").readText() +
                File("src/main/kotlin/app/clearsms/ui/components/ExtractKeyLabels.kt").readText()
        val referenced = Regex("""R\.string\.(\w+)""").findAll(labels).map { it.groupValues[1] }.toSet()
        // 21 category / sub-category labels + 26 extract keys + 2 transaction types.
        assertThat(referenced.size).isAtLeast(49)
        for (values in listOf("values", "values-hi")) {
            val xml = File("src/main/res/$values/strings.xml").readText()
            val defined = Regex("""<string name="(\w+)"""").findAll(xml).map { it.groupValues[1] }.toSet()
            assertWithMessage("$values/strings.xml is missing").that(referenced - defined).isEmpty()
        }
        // The words RelativeTime shows between dates, likewise.
        for (values in listOf("values", "values-hi")) {
            val xml = File("src/main/res/$values/strings.xml").readText()
            assertThat(xml).contains("name=\"date_today\"")
            assertThat(xml).contains("name=\"date_yesterday\"")
        }
    }
}
