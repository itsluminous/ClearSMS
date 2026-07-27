package app.clearsms.data.rules

import com.google.common.truth.Truth.assertThat
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertThrows
import org.junit.Test

class RuleImporterTest {
    private val importer = RuleImporter(Json { ignoreUnknownKeys = true })

    private fun document(vararg rules: String): String = """{"version":"1.0","rules":[${rules.joinToString(",")}]}"""

    private fun rule(
        id: String,
        bodyPattern: String,
    ): String =
        """
        {"id":"$id","priority":1,
         "match":{"body_pattern":${JsonPrimitive(bodyPattern)}},
         "action":{"category":"promotional"}}
        """.trimIndent()

    @Test
    fun `valid document imports as user rules`() {
        val rows = importer.import(document(rule("ok-1", "(?i)flash sale")))
        assertThat(rows).hasSize(1)
        assertThat(rows.single().source).isEqualTo(RuleSources.USER)
    }

    @Test
    fun `garbage input is rejected`() {
        assertThrows(IllegalArgumentException::class.java) { importer.import("not json") }
    }

    @Test
    fun `pattern with leading dot-star wrapper is rejected`() {
        val e =
            assertThrows(IllegalArgumentException::class.java) {
                importer.import(document(rule("bad-1", ".*sale.*")))
            }
        assertThat(e).hasMessageThat().contains("bad-1")
    }

    @Test
    fun `pattern with leading whitespace-class wrapper is rejected even after flags`() {
        assertThrows(IllegalArgumentException::class.java) {
            importer.import(document(rule("bad-2", """(?i)[\s\S]*debited""")))
        }
    }

    @Test
    fun `oversized pattern is rejected`() {
        val huge = "a".repeat(RuleImporter.MAX_PATTERN_LENGTH + 1)
        val e =
            assertThrows(IllegalArgumentException::class.java) {
                importer.import(document(rule("bad-3", huge)))
            }
        assertThat(e).hasMessageThat().contains("maximum ${RuleImporter.MAX_PATTERN_LENGTH}")
    }

    @Test
    fun `document with too many rules is rejected`() {
        val many = (1..RuleImporter.MAX_RULES_PER_IMPORT + 1).map { rule("r-$it", "(?i)x$it") }
        val e =
            assertThrows(IllegalArgumentException::class.java) {
                importer.import(document(*many.toTypedArray()))
            }
        assertThat(e).hasMessageThat().contains("Too many rules")
    }

    @Test
    fun `wildcards in the middle of a pattern are allowed`() {
        val rows = importer.import(document(rule("ok-2", """(?i)debited[\s\S]{0,40}?account""")))
        assertThat(rows).hasSize(1)
    }
}
