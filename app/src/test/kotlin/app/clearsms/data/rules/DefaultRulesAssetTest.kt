package app.clearsms.data.rules

import app.clearsms.domain.model.Category
import app.clearsms.domain.model.SubCategory
import com.google.common.truth.Truth.assertThat
import kotlinx.serialization.json.Json
import org.junit.Test
import java.io.File

/**
 * Validates the serialization models and rule engine against the REAL bundled
 * rules asset that ships in the APK (app/src/main/assets/default_rules.json).
 */
class DefaultRulesAssetTest {
    private val json = Json { ignoreUnknownKeys = true }

    private fun assetText(): String {
        val candidates =
            listOf(
                File("src/main/assets/default_rules.json"),
                File("app/src/main/assets/default_rules.json"),
            )
        val file = candidates.firstOrNull { it.exists() }
        checkNotNull(file) { "default_rules.json asset not found from working dir ${File(".").absolutePath}" }
        return file.readText()
    }

    private fun document(): RuleDocument = json.decodeFromString(RuleDocument.serializer(), assetText())

    @Test
    fun `bundled document parses with contract models`() {
        val document = document()
        assertThat(document.version).isEqualTo("1.1")
        assertThat(document.rules).isNotEmpty()
    }

    @Test
    fun `all rule ids are unique`() {
        val ids = document().rules.map { it.id }
        assertThat(ids).containsNoDuplicates()
    }

    @Test
    fun `all regex patterns compile`() {
        for (rule in document().rules) {
            rule.match.senderPattern?.let { Regex(it) }
            rule.match.bodyPattern?.let { Regex(it) }
        }
    }

    @Test
    fun `all action categories are known`() {
        val known = setOf("important", "promotional", "informational", "personal", "otp", "unknown")
        for (rule in document().rules) {
            assertThat(known).contains(rule.action.category.lowercase())
        }
    }

    @Test
    fun `engine matches a real hdfc debit sms with extraction`() {
        val result =
            RuleEngine().evaluate(
                document().rules,
                sender = "VM-HDFCBK-S",
                body = "Sent Rs.500.00 From HDFC Bank A/C x1234 To SWIGGY On 12/07/26 Ref 519912345678 Not You? Call 18002586161",
            )
        assertThat(result).isNotNull()
        assertThat(result!!.category).isEqualTo(Category.IMPORTANT)
        assertThat(result.subCategory).isEqualTo(SubCategory.TRANSACTION)
        assertThat(result.extracted["amount"]).isEqualTo("500.00")
        assertThat(result.extracted["account_last4"]).isEqualTo("1234")
        assertThat(result.extracted["type"]).isEqualTo("debit")
    }

    @Test
    fun `engine matches a generic otp sms`() {
        val result =
            RuleEngine().evaluate(
                document().rules,
                sender = "VM-BOOKMY",
                body = "Your OTP is 4821 for the booking. Valid for 10 minutes.",
            )
        assertThat(result).isNotNull()
        assertThat(result!!.category).isEqualTo(Category.OTP)
        assertThat(result.extracted["otp_code"]).isEqualTo("4821")
    }

    @Test
    fun `round trips through entity mapping`() {
        val document = document()
        for (definition in document.rules.take(20)) {
            val entity = definition.toEntity(json, RuleSources.BUILTIN)
            val back = entity.toDefinition(json)
            assertThat(back).isNotNull()
            assertThat(back!!.match).isEqualTo(definition.match)
            assertThat(back.action).isEqualTo(definition.action)
            assertThat(back.priority).isEqualTo(definition.priority)
        }
    }
}
