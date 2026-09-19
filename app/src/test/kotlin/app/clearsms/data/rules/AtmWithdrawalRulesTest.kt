package app.clearsms.data.rules

import app.clearsms.domain.model.Category
import app.clearsms.domain.model.SubCategory
import com.google.common.truth.Truth.assertThat
import kotlinx.serialization.json.Json
import org.junit.Test
import java.io.File

/**
 * The ATM cash-withdrawal shape (issue #43): "Rs.X withdrawn from A/c
 * ...NNNN at ATM TID ..." with an ellipsis-masked tail and a trailing
 * cash-not-dispensed advisory. Every message here is SYNTHETIC - digits,
 * TIDs, refs and helplines were invented for these tests.
 *
 * The rule deliberately requires all three pieces of evidence: an amount,
 * the exact "withdrawn from a/c <tail>" phrase, and an ATM token within 40
 * chars of the tail. Branch/cheque withdrawals without an ATM mention and
 * card-at-ATM shapes (hdfc-atm-card rules) are deliberately NOT matched, and
 * promotional "withdraw cash at any ATM" pitches carry no
 * amount-before-verb so they can never match.
 */
class AtmWithdrawalRulesTest {
    private val json = Json { ignoreUnknownKeys = true }
    private val engine = RuleEngine()

    private val rules: List<RuleDefinition> by lazy {
        val file =
            listOf(
                File("src/main/assets/default_rules.json"),
                File("app/src/main/assets/default_rules.json"),
            ).firstOrNull { it.exists() }
        checkNotNull(file) { "default_rules.json not found" }
        json.decodeFromString(RuleDocument.serializer(), file.readText()).rules
    }

    private fun evaluate(
        sender: String,
        body: String,
    ) = engine.evaluate(rules, sender, body)

    private val bobAtmBody =
        "Rs.4500.00 withdrawn from A/c ...2871 at ATM TID 9QYyyyk47 Ref.3186 " +
            "Avlbal Amt:Rs.6120.55(12-09-2026 14:05:09).In case your a/c is debited but cash is " +
            "not dispensed from the ATM, the transaction will be automatically reversed within " +
            "48 hours. TC apply. If not used by you, call 18005701-BOB"

    @Test
    fun `bob atm withdrawal with ellipsis tail matches with captures`() {
        val result = evaluate("AD-BOBTXN-S", bobAtmBody)
        assertThat(result?.matchedRuleId).isEqualTo("atm-withdrawal-01")
        assertThat(result?.category).isEqualTo(Category.IMPORTANT)
        assertThat(result?.subCategory).isEqualTo(SubCategory.TRANSACTION)
        assertThat(result?.extracted?.get("amount")).isEqualTo("4500.00")
        assertThat(result?.extracted?.get("account_last4")).isEqualTo("2871")
        assertThat(result?.extracted?.get("type")).isEqualTo("debit")
    }

    @Test
    fun `reversal advisory never makes the withdrawal promotional`() {
        val result = evaluate("AD-BOBTXN-S", bobAtmBody)
        assertThat(result?.category).isNotEqualTo(Category.PROMOTIONAL)
    }

    @Test
    fun `other banks x-masked atm wording matches too`() {
        val result =
            evaluate(
                "VM-CANBNK-S",
                "INR 2,000.00 withdrawn from A/c XX7304 at CANARA BANK ATM on 12-09-2026. Avl Bal INR 5,410.20",
            )
        assertThat(result?.matchedRuleId).isEqualTo("atm-withdrawal-01")
        assertThat(result?.extracted?.get("amount")).isEqualTo("2,000.00")
        assertThat(result?.extracted?.get("account_last4")).isEqualTo("7304")
    }

    @Test
    fun `withdrawal without any atm evidence is left to the generic safety net`() {
        val result =
            evaluate(
                "VM-CANBNK-S",
                "INR 2,000.00 withdrawn from A/c XX7304 at BRANCH counter on 12-09-2026. Avl Bal INR 5,410.20",
            )
        assertThat(result?.matchedRuleId).isNotEqualTo("atm-withdrawal-01")
        // Still a transaction via the generic debit rule - never promotional.
        assertThat(result?.matchedRuleId).isEqualTo("generic-debit-01")
    }

    @Test
    fun `atm marketing pitch without the withdrawn-from phrase never matches`() {
        val result =
            evaluate(
                "BP-OFFERS-P",
                "Withdraw cash from any ATM without your debit card! Get Rs.50 cashback on your first UPI-ATM withdrawal.",
            )
        assertThat(result?.matchedRuleId).isNotEqualTo("atm-withdrawal-01")
    }
}
