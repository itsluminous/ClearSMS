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
 * 2. a label derived from an enum NAME (`name.lowercase().replace('_', ' ')`),
 *    which is unlocalizable by construction.
 *
 * It stays narrow on purpose: branch results that are codes, MIME types,
 * paths, keys, interpolations or lower-case identifiers are not words a
 * user reads and are ignored, and the diagnostics package is skipped
 * because the shareable report is deliberately English (see
 * [DiagnosticLogConventionTest]). Verified against planted literals in the
 * self-test below and against the real `CategoryBadge.kt` before the fix.
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

    /** `name.lowercase().replace('_', ' ')` - humanising an enum name into a label. */
    private val enumNameAsLabel = Regex("""\.lowercase\(\)\s*\.replace\('_',\s*' '\)""")

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
            if (enumNameAsLabel.containsMatchIn(line)) Finding(path, index + 1, line) else null
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
    fun `no label is manufactured from an enum name`() {
        val findings = sources().flatMap { enumNameLabels(it.path, it.readText()) }.toList()
        assertWithMessage(
            "Enum names are STORED values; a label for one is a string resource, " +
                "never name.lowercase().replace('_', ' '):\n" + findings.joinToString("\n"),
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
            """.trimIndent()
        val branches = literalBranches("planted.kt", planted)
        // Two enum-constant labels (one of them the all-caps OTP), a wrapped
        // branch, an else-branch phrase and the relative-date word. NOT the
        // resource id, the MIME types, the diagnostic transport codes (all
        // caps, but behind a boolean / else condition), the lower-case SQL
        // value, the string-keyed map entry or the interpolation.
        assertThat(branches.map { it.line }).containsExactly(3, 4, 6, 8, 11).inOrder()
        assertThat(enumNameLabels("planted.kt", planted).map { it.line }).containsExactly(27)
    }

    @Test
    fun `every category and sub-category label resource exists in English and in Hindi`() {
        // The one-definition file: every R.string it names must resolve in
        // both languages, or a Hindi user gets English (or a crash) back.
        val labels = File("src/main/kotlin/app/clearsms/ui/components/CategoryLabels.kt").readText()
        val referenced = Regex("""R\.string\.(\w+)""").findAll(labels).map { it.groupValues[1] }.toSet()
        assertThat(referenced.size).isAtLeast(21)
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
