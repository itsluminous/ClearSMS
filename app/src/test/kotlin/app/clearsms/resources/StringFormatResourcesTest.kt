package app.clearsms.resources

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.io.File
import java.util.Formatter

/**
 * Guards every string resource that carries format arguments against malformed
 * specifiers.
 *
 * A literal percent sign must be escaped as `%%` inside a formatted string:
 * `"above the safe 30% limit"` makes [Formatter] read `% l` as a conversion
 * named `'l'` and throw [java.util.UnknownFormatConversionException] at runtime.
 * That shipped once (the Finance credit-card usage banner crashed the tab as
 * soon as a card had a known total limit) and neither Android Lint nor the
 * compiler caught it, so it is asserted here instead.
 */
class StringFormatResourcesTest {
    private val valuesDir = File("src/main/res/values")

    /** `%1$s`, `%2$d`, `%1$.2f` … — a positional argument reference. */
    private val positional = Regex("""%\d+\$[-#+ 0,(]*\d*(?:\.\d+)?[a-zA-Z]""")

    @Test
    fun `every string resource has only well-formed format specifiers`() {
        val offenders = mutableListOf<String>()

        stringResources().forEach { (name, value) ->
            // Blank out the constructs that are legal, then nothing containing a
            // percent sign should remain.
            val residue =
                value
                    .replace(positional, "")
                    .replace("%%", "")
            if (residue.contains('%')) {
                offenders += "$name -> \"$value\""
            }
        }

        assertThat(offenders).isEmpty()
    }

    @Test
    fun `formatted string resources survive a real format call`() {
        stringResources()
            .filter { (_, value) -> positional.containsMatchIn(value) }
            .forEach { (name, value) ->
                // Build arguments whose types match each specifier's conversion,
                // so only malformed specifiers can make the call fail.
                val args = argumentsFor(value)
                val formatted =
                    runCatching { String.format(value, *args) }
                        .getOrElse { error("Resource '$name' is not formattable: \"$value\" (${it.message})") }
                assertThat(formatted).isNotEmpty()
            }
    }

    /**
     * Produces a correctly typed argument per positional index found in [value]:
     * an Int for `d`, a Double for `f`/`e`/`g`, a String otherwise.
     */
    private fun argumentsFor(value: String): Array<Any> {
        val byIndex = sortedMapOf<Int, Any>()
        positional.findAll(value).forEach { match ->
            val spec = match.value
            val index = spec.substringAfter('%').substringBefore('$').toInt()
            byIndex[index] =
                when (spec.last()) {
                    'd' -> 1
                    'f', 'e', 'E', 'g', 'G' -> 1.0
                    else -> "x"
                }
        }
        val highest = byIndex.lastKey()
        // Fill any gap so String.format never sees a missing argument.
        return Array(highest) { i -> byIndex[i + 1] ?: "x" }
    }

    /** Reads `name to value` for every `<string>` under res/values. */
    private fun stringResources(): List<Pair<String, String>> {
        val files = valuesDir.listFiles { f: File -> f.name.endsWith(".xml") }.orEmpty()
        assertThat(files).isNotEmpty()

        val entry = Regex("""<string name="([^"]+)"[^>]*>(.*?)</string>""", RegexOption.DOT_MATCHES_ALL)
        return files.flatMap { file ->
            entry.findAll(file.readText()).map { it.groupValues[1] to it.groupValues[2] }
        }
    }
}
