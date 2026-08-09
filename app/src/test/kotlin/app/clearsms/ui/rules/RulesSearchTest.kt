package app.clearsms.ui.rules

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class RulesSearchTest {
    private fun rule(
        id: String,
        name: String,
    ) = RuleItem(id = id, name = name, isUserDefined = false, enabled = true)

    private val rules =
        listOf(
            rule("hdfc-debit-01", "HDFC Bank Debit Transaction"),
            rule("sbi-credit-02", "SBI Credit"),
            rule("generic-otp-05", "Generic OTP"),
        )

    @Test
    fun `blank query keeps everything`() {
        assertThat(filterRules(rules, "")).isEqualTo(rules)
        assertThat(filterRules(rules, "   ")).isEqualTo(rules)
    }

    @Test
    fun `matches name case-insensitively`() {
        assertThat(filterRules(rules, "hdfc bank").map { it.id }).containsExactly("hdfc-debit-01")
    }

    @Test
    fun `matches the rule id too`() {
        assertThat(filterRules(rules, "otp-05").map { it.id }).containsExactly("generic-otp-05")
    }

    @Test
    fun `no match yields empty`() {
        assertThat(filterRules(rules, "icici")).isEmpty()
    }
}
