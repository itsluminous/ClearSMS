package app.clearsms

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import org.junit.Test
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Keeps the generated translation status block in `README.md` honest.
 *
 * WHY: the README advertises per-language coverage, and a status table that
 * silently rots is worse than none. `scripts/translation_status.py` writes
 * the block; this test recomputes the same numbers straight from the
 * resource files and fails when the README disagrees, so a translation PR
 * that forgets to regenerate the table cannot land. The counting rule is
 * deliberately duplicated here rather than shelling out to Python: the
 * test must run wherever the unit tests run.
 *
 * Counting rule (identical to the script's docstring): denominator = every
 * `<string>`, `<plurals>` and `<string-array>` in `values/` without
 * `translatable="false"`, a plurals counting as ONE item; numerator = how
 * many of those names the locale's directory defines. Coverage measures
 * presence, not quality.
 *
 * Only genuine locale qualifiers (`ru`, `pt-rBR`, `b+sr+Latn`) count as
 * languages. `values-night` already exists in this repo and is a theme
 * qualifier, not a language; `values-v31`, `values-land`, `values-sw600dp`
 * and a locale combined with another qualifier are likewise excluded.
 */
class TranslationStatusTest {
    private val resDir = repoDir("app/src/main/res")
    private val readme = repoFile("README.md")

    @Test
    fun `README translation status block matches the resource files`() {
        val total = translatableNames(resDir.resolve("values"), respectFlag = true)
        val rows =
            localeDirs(resDir).map { (qualifier, dir) ->
                qualifier to (translatableNames(dir, respectFlag = false) intersect total).size
            }
        val expected = render(total.size, rows)
        val actual = markedBlock(readme.readText())
        assertWithMessage(
            "README.md translation status is stale - run python3 scripts/translation_status.py",
        ).that(actual).isEqualTo(expected)
    }

    @Test
    fun `the default locale has the expected shape`() {
        val values = resDir.resolve("values")
        val all = translatableNames(values, respectFlag = false)
        val translatable = translatableNames(values, respectFlag = true)
        // Sanity: there are many strings, and translatable="false" entries exist and are excluded.
        assertThat(translatable.size).isGreaterThan(500)
        assertThat(all.size).isGreaterThan(translatable.size)
        assertThat(translatable).doesNotContain("url_source_code")
        assertThat(translatable).contains("app_name")
    }

    @Test
    fun `values-night and other non-locale qualifiers are not languages`() {
        // The repo really has values-night; it must never appear in the table.
        assertThat(resDir.resolve("values-night").isDirectory).isTrue()
        assertThat(localeDirs(resDir).map { it.first }).doesNotContain("night")

        listOf("night", "v31", "land", "sw600dp", "ldrtl", "w820dp", "xhdpi", "ru-night", "en-rUS-v21", "car")
            .forEach { assertWithMessage(it).that(isLocaleQualifier(it)).isFalse() }
        listOf("ru", "de", "pt-rBR", "zh-rTW", "b+sr+Latn", "b+zh+Hans", "b+es+419")
            .forEach { assertWithMessage(it).that(isLocaleQualifier(it)).isTrue() }
    }

    @Test
    fun `a plurals counts as one item regardless of its quantities`() {
        val dir = java.nio.file.Files.createTempDirectory("values-xx").toFile()
        try {
            dir.resolve("strings.xml").writeText(
                """
                <resources>
                    <string name="a">A</string>
                    <string name="b" translatable="false">B</string>
                    <plurals name="c">
                        <item quantity="one">%d</item>
                        <item quantity="few">%d</item>
                        <item quantity="many">%d</item>
                        <item quantity="other">%d</item>
                    </plurals>
                    <string-array name="d"><item>x</item><item>y</item></string-array>
                </resources>
                """.trimIndent(),
            )
            assertThat(translatableNames(dir, respectFlag = true)).containsExactly("a", "c", "d")
            assertThat(translatableNames(dir, respectFlag = false)).containsExactly("a", "b", "c", "d")
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `rendering with zero translations states that plainly`() {
        val block = render(743, emptyList())
        assertThat(block).contains("No translations yet")
        assertThat(block).contains("743 translatable strings")
        assertThat(block).doesNotContain("| Language |")
    }

    @Test
    fun `rendering a locale truncates the percentage and names the language`() {
        val block = render(743, listOf("ru" to 742, "b+sr+Latn" to 743))
        assertThat(block).contains("| Russian | `ru` | 742 / 743 | 99% |")
        assertThat(block).contains("| Serbian (Latin) | `b+sr+Latn` | 743 / 743 | 100% |")
        assertThat(block).contains("measures presence, not quality")
    }

    // --- the counting rule, mirrored from scripts/translation_status.py ---

    private val legacyLocale = Regex("^[a-z]{2}(-r[A-Z]{2})?$")
    private val bcp47Locale = Regex("^b\\+[a-zA-Z]{2,3}(\\+[a-zA-Z0-9]{1,8})*$")

    private fun isLocaleQualifier(qualifier: String) =
        legacyLocale.matches(qualifier) || bcp47Locale.matches(qualifier)

    private fun localeDirs(res: File): List<Pair<String, File>> =
        res.listFiles()!!
            .filter { it.isDirectory && it.name.startsWith("values-") }
            .map { it.name.removePrefix("values-") to it }
            .filter { isLocaleQualifier(it.first) }
            .sortedBy { it.first }

    private fun translatableNames(
        dir: File,
        respectFlag: Boolean,
    ): Set<String> {
        val factory = DocumentBuilderFactory.newInstance()
        val names = mutableSetOf<String>()
        dir.listFiles()!!.filter { it.extension == "xml" }.sortedBy { it.name }.forEach { file ->
            val root = factory.newDocumentBuilder().parse(file).documentElement
            if (root.tagName != "resources") return@forEach
            val children = root.childNodes
            for (i in 0 until children.length) {
                val el = children.item(i) as? org.w3c.dom.Element ?: continue
                if (el.tagName !in setOf("string", "plurals", "string-array")) continue
                if (respectFlag && el.getAttribute("translatable") == "false") continue
                el.getAttribute("name").takeIf { it.isNotEmpty() }?.let(names::add)
            }
        }
        return names
    }

    private fun render(
        total: Int,
        rows: List<Pair<String, Int>>,
    ): String {
        val lines = mutableListOf(GENERATED_NOTE)
        if (rows.isEmpty()) {
            lines +=
                "No translations yet - the app ships in English only, with $total translatable strings " +
                "(each plural counted once). See [docs/translating.md](docs/translating.md) to add one."
            return lines.joinToString("\n")
        }
        lines += "| Language | Code | Translated | Coverage |"
        lines += "| --- | --- | ---: | ---: |"
        rows.forEach { (code, count) ->
            val pct = if (total == 0) 0 else count * 100 / total
            lines += "| ${LANGUAGE_NAMES[code] ?: code} | `$code` | $count / $total | $pct% |"
        }
        lines += ""
        lines +=
            "Coverage counts strings the translation defines, out of $total translatable items in English " +
            "(each plural counted once); it measures presence, not quality. " +
            "Regenerate with `python3 scripts/translation_status.py`."
        return lines.joinToString("\n")
    }

    private fun markedBlock(readme: String): String {
        val start = readme.indexOf(START_MARKER)
        val end = readme.indexOf(END_MARKER)
        assertWithMessage("README.md must contain the translation-status markers").that(start).isAtLeast(0)
        assertThat(end).isGreaterThan(start)
        return readme.substring(start + START_MARKER.length, end).trim('\n')
    }

    private companion object {
        const val START_MARKER = "<!-- translation-status:start -->"
        const val END_MARKER = "<!-- translation-status:end -->"
        const val GENERATED_NOTE = "<!-- Generated by scripts/translation_status.py - do not edit by hand. -->"

        /** Display names the script knows; anything else renders as its code. Keep in sync. */
        val LANGUAGE_NAMES =
            mapOf(
                "ar" to "Arabic", "bn" to "Bengali", "cs" to "Czech", "da" to "Danish", "de" to "German",
                "el" to "Greek", "es" to "Spanish", "fa" to "Persian", "fi" to "Finnish", "fr" to "French",
                "gu" to "Gujarati", "he" to "Hebrew", "hi" to "Hindi", "hu" to "Hungarian", "id" to "Indonesian",
                "in" to "Indonesian", "it" to "Italian", "iw" to "Hebrew", "ja" to "Japanese", "kn" to "Kannada",
                "ko" to "Korean", "ml" to "Malayalam", "mr" to "Marathi", "nb" to "Norwegian Bokmål",
                "nl" to "Dutch", "pl" to "Polish", "pt" to "Portuguese", "pt-rBR" to "Portuguese (Brazil)",
                "pt-rPT" to "Portuguese (Portugal)", "ro" to "Romanian", "ru" to "Russian", "sk" to "Slovak",
                "sv" to "Swedish", "ta" to "Tamil", "te" to "Telugu", "th" to "Thai", "tr" to "Turkish",
                "uk" to "Ukrainian", "vi" to "Vietnamese", "zh-rCN" to "Chinese (Simplified)",
                "zh-rTW" to "Chinese (Traditional)", "b+sr+Latn" to "Serbian (Latin)",
                "b+zh+Hans" to "Chinese (Simplified)", "b+zh+Hant" to "Chinese (Traditional)",
            )

        /** Locates a repo file whether the test runs from the module (`app/`) or the repo root. */
        fun repoFile(repoRelativePath: String): File =
            listOf(File(repoRelativePath), File(repoRelativePath.removePrefix("app/")), File("..", repoRelativePath))
                .firstOrNull { it.isFile }
                ?: error("Cannot locate $repoRelativePath from ${File(".").absolutePath}")

        fun repoDir(repoRelativePath: String): File =
            listOf(File(repoRelativePath), File(repoRelativePath.removePrefix("app/")), File("..", repoRelativePath))
                .firstOrNull { it.isDirectory }
                ?: error("Cannot locate $repoRelativePath from ${File(".").absolutePath}")
    }
}
