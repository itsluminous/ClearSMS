package app.clearsms.domain.rules

import app.clearsms.data.rules.RuleEngine
import app.clearsms.data.rules.RuleExporter
import app.clearsms.data.rules.RuleImporter
import app.clearsms.data.rules.RuleSources
import app.clearsms.data.rules.toDefinition
import app.clearsms.data.rules.toEntity
import app.clearsms.domain.model.Category
import com.google.common.truth.Truth.assertThat
import kotlinx.serialization.json.Json
import org.junit.Test

/**
 * The one-step sender rule (issue #38): "messages from THIS sender are always
 * <category>", built by the app from a tapped sender - no pattern to type and
 * nothing the wizard's validation can reject. It is an ordinary sender-bound
 * user rule, so it must behave like one everywhere: match every route variant
 * of the sender, take the immediate re-sort path, survive export/import.
 */
class SenderRuleTest {
    private val json = Json { ignoreUnknownKeys = true }
    private val engine = RuleEngine()

    @Test
    fun `a rule can be built for every category and matches the sender's messages`() {
        for (category in SenderRule.CATEGORIES) {
            val rule = SenderRule.definition("VM-HDFCBK-S", category, id = "user_$category")

            assertThat(rule.match.senderPattern).isEqualTo("(?i)HDFCBK")
            assertThat(rule.match.bodyPattern).isNull()
            assertThat(rule.action.category).isEqualTo(category)
            assertThat(rule.priority).isEqualTo(SenderRule.USER_BAND_PRIORITY)
            val result = engine.evaluate(listOf(rule), "AD-HDFCBK", "Any body at all, promo or not")
            assertThat(result?.category).isEqualTo(RuleEngine.categoryOf(category))
            assertThat(result?.matchedRuleId).isEqualTo("user_$category")
        }
    }

    @Test
    fun `categories offered are exactly the app's primary categories`() {
        assertThat(SenderRule.CATEGORIES.map { RuleEngine.categoryOf(it) })
            .containsExactlyElementsIn(Category.entries)
    }

    @Test
    fun `regex-special characters in a sender id are escaped, so the pattern matches itself only`() {
        val specials = "AB.C+D*(E)[F]|G?H^\$"
        val pattern = SenderRule.pattern("AX-$specials")

        assertThat(pattern).isEqualTo("(?i)" + RuleComposer.escapeLiteral(specials))
        val regex = Regex(pattern) // must compile
        assertThat(regex.containsMatchIn("AX-$specials")).isTrue()
        assertThat(regex.containsMatchIn(specials.lowercase())).isTrue()
        // Unescaped, "AB.C" would match "ABXC" and "(E)" would be a group.
        assertThat(regex.containsMatchIn("ABXC+D*(E)[F]|G?H^\$")).isFalse()
        assertThat(regex.containsMatchIn("ABC")).isFalse()
    }

    @Test
    fun `a phone-number sender reduces to its last ten digits, matching every dialling variant`() {
        val rule = SenderRule.definition("+91 98765 43210", "personal", id = "user_phone")

        assertThat(rule.match.senderPattern).isEqualTo("(?i)9876543210")
        for (variant in listOf("+919876543210", "9876543210", "09876543210")) {
            assertThat(engine.evaluate(listOf(rule), variant, "hi")).isNotNull()
        }
        assertThat(engine.evaluate(listOf(rule), "+919876543211", "hi")).isNull()
    }

    @Test
    fun `the wizard's sender pattern and the one-tap rule agree, so both scope the same way`() {
        for (sender in listOf("VM-HDFCBK-S", "AX-AB.CD", "+919876543210", "JD-AMAZON")) {
            assertThat(RuleSuggester.senderPattern(sender)).isEqualTo(SenderRule.pattern(sender))
        }
    }

    @Test
    fun `a sender rule always resolves to the immediate sender scope, never the full re-sort`() {
        for (sender in listOf("VM-HDFCBK-S", "AX-AB.CD", "A+B", "+91 98765 43210", "JD-ICICIB-P")) {
            val rule = SenderRule.definition(sender, "promotional", id = "user_x")
            val scope =
                RuleScopeResolver.resolve(
                    senderPattern = rule.match.senderPattern.orEmpty(),
                    sourceSender = sender,
                    boundToSender = true,
                    senderPatternEdited = false,
                )
            assertThat(scope).isEqualTo(RuleApplyScope.Sender(SenderRule.senderCore(sender)))
        }
    }

    @Test
    fun `the rule round-trips through rules export and import unchanged`() {
        val rule = SenderRule.definition("AX-AB.CD", "promotional", id = "user_roundtrip")
        val exported = RuleExporter(json).export(listOf(rule.toEntity(json, RuleSources.USER)))

        val imported = RuleImporter(json).import(exported)

        assertThat(imported).hasSize(1)
        assertThat(imported.single().source).isEqualTo(RuleSources.USER)
        // Storage stamps created_at; every rule-defining field is identical.
        assertThat(imported.single().toDefinition(json)?.copy(createdAt = null)).isEqualTo(rule)
    }

    @Test
    fun `a blank sender cannot build a rule`() {
        assertThat(SenderRule.canBuild("")).isFalse()
        assertThat(SenderRule.canBuild("   ")).isFalse()
        assertThat(SenderRule.canBuild("VM-HDFCBK")).isTrue()
    }

    @Test
    fun `an unknown category is refused rather than stored`() {
        val error = runCatching { SenderRule.definition("VM-HDFCBK", "spam") }.exceptionOrNull()
        assertThat(error).isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `isSenderOnly recognises the shape this object builds and nothing richer`() {
        val plain = SenderRule.definition("VM-HDFCBK", "otp", id = "user_a")
        assertThat(SenderRule.isSenderOnly(plain)).isTrue()
        val withBody = plain.copy(match = plain.match.copy(bodyPattern = "OTP is (\\d{6})"))
        assertThat(SenderRule.isSenderOnly(withBody)).isFalse()
        val withKeyword = plain.copy(match = plain.match.copy(bodyMustContain = listOf("otp")))
        assertThat(SenderRule.isSenderOnly(withKeyword)).isFalse()
    }
}
