package app.clearsms.data.rules

import app.clearsms.domain.model.Category
import app.clearsms.domain.model.SubCategory
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class RuleEngineTest {
    private val engine = RuleEngine()

    private fun rule(
        id: String,
        priority: Int,
        match: RuleMatch = RuleMatch(),
        action: RuleAction = RuleAction(category = "important"),
    ) = RuleDefinition(id = id, name = id, priority = priority, match = match, action = action)

    @Test
    fun `higher priority rule wins`() {
        val rules =
            listOf(
                rule("low", 10, RuleMatch(bodyPattern = "(?i)hello"), RuleAction(category = "promotional")),
                rule("high", 100, RuleMatch(bodyPattern = "(?i)hello"), RuleAction(category = "important")),
            )
        val result = engine.evaluate(rules, "SENDER", "Hello world")
        assertThat(result?.matchedRuleId).isEqualTo("high")
        assertThat(result?.category).isEqualTo(Category.IMPORTANT)
    }

    @Test
    fun `sender pattern must match when present`() {
        val rules =
            listOf(
                rule("hdfc", 100, RuleMatch(senderPattern = "(?i).*HDFC.*", bodyPattern = "(?i)debited")),
            )
        assertThat(engine.evaluate(rules, "VM-HDFCBK", "Rs.100 debited")).isNotNull()
        assertThat(engine.evaluate(rules, "VM-ICICIB", "Rs.100 debited")).isNull()
    }

    @Test
    fun `body must contain all terms case insensitively`() {
        val rules =
            listOf(
                rule("r1", 100, RuleMatch(bodyMustContain = listOf("debited", "upi"))),
            )
        assertThat(engine.evaluate(rules, "S", "Amount DEBITED via UPI")).isNotNull()
        assertThat(engine.evaluate(rules, "S", "Amount DEBITED via NEFT")).isNull()
    }

    @Test
    fun `body must not contain blocks the match`() {
        val rules =
            listOf(
                rule("r1", 100, RuleMatch(bodyPattern = "(?i)debited", bodyMustNotContain = listOf("otp"))),
            )
        assertThat(engine.evaluate(rules, "S", "Rs.100 debited from account")).isNotNull()
        assertThat(engine.evaluate(rules, "S", "OTP for Rs.100 debited txn is 1234")).isNull()
    }

    @Test
    fun `dollar group references resolve from body pattern groups`() {
        val rules =
            listOf(
                rule(
                    "tx",
                    100,
                    RuleMatch(bodyPattern = "(?i)Rs\\.?\\s*([\\d,]+\\.?\\d*) debited from a/c \\w*(\\d{4})"),
                    RuleAction(
                        category = "important",
                        subCategory = "transaction",
                        extract = mapOf("amount" to "$1", "account_last4" to "$2", "type" to "debit"),
                    ),
                ),
            )
        val result = engine.evaluate(rules, "HDFCBK", "Rs.2,500.00 debited from A/c XX1234 on 12-07-26")
        assertThat(result).isNotNull()
        assertThat(result!!.extracted["amount"]).isEqualTo("2,500.00")
        assertThat(result.extracted["account_last4"]).isEqualTo("1234")
        assertThat(result.extracted["type"]).isEqualTo("debit")
        assertThat(result.subCategory).isEqualTo(SubCategory.TRANSACTION)
    }

    @Test
    fun `invalid regex rule is skipped and logged`() {
        val logged = mutableListOf<String>()
        val engine = RuleEngine(log = { logged += it })
        val rules =
            listOf(
                rule("broken", 200, RuleMatch(bodyPattern = "([")),
                rule("valid", 50, RuleMatch(bodyPattern = "(?i)hello"), RuleAction(category = "promotional")),
            )
        val result = engine.evaluate(rules, "S", "hello there")
        assertThat(result?.matchedRuleId).isEqualTo("valid")
        assertThat(logged).isNotEmpty()
        assertThat(logged.first()).contains("broken")
    }

    @Test
    fun `no rules match returns null`() {
        val rules = listOf(rule("r1", 10, RuleMatch(bodyPattern = "(?i)nomatch")))
        assertThat(engine.evaluate(rules, "S", "hello")).isNull()
    }

    @Test
    fun `category and sub category strings map to enums`() {
        assertThat(RuleEngine.categoryOf("otp")).isEqualTo(Category.OTP)
        assertThat(RuleEngine.categoryOf("IMPORTANT")).isEqualTo(Category.IMPORTANT)
        assertThat(RuleEngine.categoryOf("promotional")).isEqualTo(Category.PROMOTIONAL)
        assertThat(RuleEngine.categoryOf("weird")).isEqualTo(Category.UNKNOWN)
        assertThat(RuleEngine.subCategoryOf("bank_alert")).isEqualTo(SubCategory.BANK_ALERT)
        assertThat(RuleEngine.subCategoryOf("scam")).isEqualTo(SubCategory.SCAM)
        assertThat(RuleEngine.subCategoryOf(null)).isNull()
        assertThat(RuleEngine.subCategoryOf("unheard_of")).isEqualTo(SubCategory.GENERAL)
    }

    @Test
    fun `rule with empty match conditions matches everything`() {
        val rules = listOf(rule("catchall", 1))
        assertThat(engine.evaluate(rules, "ANY", "any body")).isNotNull()
    }

    @Test
    fun `extract with blank group value is dropped`() {
        val rules =
            listOf(
                rule(
                    "r1",
                    10,
                    RuleMatch(bodyPattern = "(?i)(foo)?bar"),
                    RuleAction(category = "important", extract = mapOf("v" to "$1")),
                ),
            )
        val result = engine.evaluate(rules, "S", "just bar here")
        assertThat(result).isNotNull()
        assertThat(result!!.extracted).doesNotContainKey("v")
    }
}
