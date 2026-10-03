package app.clearsms.resources

import app.clearsms.testing.DefaultStrings
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import org.junit.Test
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/**
 * The default-locale string files are split by TRANSLATABILITY, and this
 * test keeps that split honest.
 *
 * WHY: `strings_notranslate.xml` exists so that a translator can ignore one
 * file and translate the rest. That only works if every entry in it really
 * is `translatable="false"` (otherwise it shows up in the status table and
 * Lint's MissingTranslation check as untranslated), and if nothing that must
 * not be translated hides elsewhere: a `url_*` string dropped into
 * `strings.xml` would be offered to translators, and a translated URL is a
 * broken link. The product name is the same case - "Clear SMS" is not a
 * phrase - which is why `app_name` lives here and not in `strings.xml`.
 */
class NoTranslateStringsConventionTest {
    private val translatedFiles = listOf("strings.xml", "strings_platform.xml")

    @Test
    fun `every entry in strings_notranslate is marked translatable=false`() {
        val entries = entries(DefaultStrings.file("strings_notranslate.xml"))
        assertThat(entries).isNotEmpty()
        val offenders = entries.filter { (_, translatable) -> translatable != "false" }.keys
        assertWithMessage("strings_notranslate.xml entries without translatable=\"false\"")
            .that(offenders)
            .isEmpty()
    }

    @Test
    fun `the product name and every url_ string live only in strings_notranslate`() {
        val noTranslate = entries(DefaultStrings.file("strings_notranslate.xml")).keys
        assertThat(noTranslate).contains("app_name")
        assertThat(noTranslate.filter { it.startsWith("url_") }).isNotEmpty()

        for (fileName in translatedFiles) {
            val names = entries(DefaultStrings.file(fileName)).keys
            assertWithMessage("$fileName must not define url_* strings - they belong in strings_notranslate.xml")
                .that(names.filter { it.startsWith("url_") })
                .isEmpty()
            assertWithMessage("$fileName must not define app_name - the product name is not translated")
                .that(names)
                .doesNotContain("app_name")
        }
    }

    @Test
    fun `the translated files carry no translatable=false entries`() {
        // A string that must not be translated belongs in strings_notranslate.xml,
        // so a translator can skip exactly one file. Flagging it in place
        // defeats the split.
        for (fileName in translatedFiles) {
            val flagged = entries(DefaultStrings.file(fileName)).filterValues { it == "false" }.keys
            assertWithMessage("$fileName has translatable=\"false\" entries; move them to strings_notranslate.xml")
                .that(flagged)
                .isEmpty()
        }
    }

    @Test
    fun `no string anywhere in the default locale looks like a URL unless it is in strings_notranslate`() {
        // The name-prefix rule above can be dodged by naming a link differently;
        // the value cannot. Links are not phrases and must never reach a translator.
        val urlLike = Regex("""^\s*(https?://|upi://|mailto:|market://|tel:)""", RegexOption.IGNORE_CASE)
        for (fileName in translatedFiles) {
            val offenders = values(DefaultStrings.file(fileName)).filterValues(urlLike::containsMatchIn).keys
            assertWithMessage("$fileName has URL-valued strings; they belong in strings_notranslate.xml")
                .that(offenders)
                .isEmpty()
        }
    }

    @Test
    fun `the default locale is exactly these three string files`() {
        // Any new file either joins the translated set or the no-translate
        // set; a fourth file with no stated rule would silently reopen the
        // "which file is this in?" question the split closed.
        val stringFiles =
            DefaultStrings.valuesDir
                .listFiles { f: File -> f.name.startsWith("strings") && f.extension == "xml" }!!
                .map { it.name }
        assertThat(stringFiles).containsExactly("strings.xml", "strings_platform.xml", "strings_notranslate.xml")
    }

    /** `name -> translatable attribute` (empty when absent) for every string-like element. */
    private fun entries(file: File): Map<String, String> =
        elements(file).associate { it.getAttribute("name") to it.getAttribute("translatable") }

    /** `name -> text` for every `<string>`. */
    private fun values(file: File): Map<String, String> =
        elements(file).filter { it.tagName == "string" }.associate { it.getAttribute("name") to it.textContent }

    private fun elements(file: File): List<org.w3c.dom.Element> {
        assertWithMessage("${file.path} must exist").that(file.isFile).isTrue()
        val root = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file).documentElement
        val children = root.childNodes
        return (0 until children.length)
            .mapNotNull { children.item(it) as? org.w3c.dom.Element }
            .filter { it.tagName in setOf("string", "plurals", "string-array") }
    }
}
