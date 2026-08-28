package app.clearsms.data.rules

import app.clearsms.domain.model.Category
import app.clearsms.domain.model.SubCategory
import app.clearsms.domain.parser.GuardId
import app.clearsms.domain.parser.GuardLibrary
import com.google.common.truth.Truth.assertThat
import kotlinx.serialization.json.Json
import org.junit.Test
import java.io.File
import java.time.LocalDate

/**
 * A BOBCARD statement SMS carries everything a credit-card bill needs - card
 * tail, total due, minimum due and a due date - but no rule read that shape,
 * so it fell through to the sender heuristics and surfaced as PROMOTIONAL
 * with no Alerts reminder. The `financial_evidence` rescue could not save it
 * either: its artifact list had no statement/minimum-due entry.
 *
 * All fixture values are SYNTHETIC.
 */
class BobcardStatementRulesTest {
    private val json = Json { ignoreUnknownKeys = true }
    private val anchor = LocalDate.of(2026, 8, 28)
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

    private val statement =
        "Statement for BOBCARD **1234 for AUG26 is generated. Pay Total: Rs 4210.5 or " +
            "Min Due: Rs 310 by 13-09-26. View/Download Statement on Mobile App. Pay via " +
            "bobcard.io/App or InstaPay/Net Banking. Avoid 3rd-party apps for timely " +
            "processing. Know more: bobcard.io/Pymt."

    private fun evaluate(
        sender: String,
        body: String,
    ) = engine.evaluate(rules, sender, body, anchor)

    @Test
    fun `the statement is an important bill, not promotional`() {
        val result = evaluate("VM-BOBCRD", statement)

        assertThat(result?.matchedRuleId).isEqualTo("bobcard-stmt-01")
        assertThat(result?.category).isEqualTo(Category.IMPORTANT)
        assertThat(result?.subCategory).isEqualTo(SubCategory.BILL)
    }

    @Test
    fun `total, minimum and due date are extracted for the reminder`() {
        val result = evaluate("VM-BOBCRD", statement)

        assertThat(result?.extracted).containsEntry("total_due", "4210.5")
        assertThat(result?.extracted).containsEntry("min_due", "310")
        assertThat(result?.extracted).containsEntry("account_last4", "1234")
        assertThat(result?.extracted).containsEntry("bank", "BOBCARD")
        assertThat(result?.typed?.get("due_date")).isNotNull()
    }

    @Test
    fun `a thousands-separated total is read whole`() {
        val result =
            evaluate(
                "VM-BOBCRD",
                "Statement for BOBCARD **9876 for SEP26 is generated. Pay Total: Rs 41,250.75 or " +
                    "Min Due: Rs 2,060 by 05-10-26.",
            )

        assertThat(result?.extracted).containsEntry("total_due", "41,250.75")
        assertThat(result?.extracted).containsEntry("min_due", "2,060")
    }

    @Test
    fun `a bobcard promotion stays promotional`() {
        // The rule must key on statement artifacts, never on the brand: an
        // offer SMS from the same sender has no statement, total or due date.
        val result =
            evaluate(
                "VM-BOBCRD",
                "Exciting offer! Get 10% cashback up to Rs 500 on your BOBCARD this festive " +
                    "season. Shop now: bobcard.io/Offers",
            )

        assertThat(result?.matchedRuleId).isNotEqualTo("bobcard-stmt-01")
    }

    @Test
    fun `an OTP from the same sender still wins`() {
        val result = evaluate("VM-BOBCRD", "OTP is 445566 for your BOBCARD transaction")

        assertThat(result?.category).isEqualTo(Category.OTP)
    }

    @Test
    fun `the evidence guard now recognises a generated statement with a minimum due`() {
        // Defence in depth: another issuer's statement, with no rule of its
        // own, must still be rescued from PROMOTIONAL by the invariant.
        assertThat(GuardLibrary.matches(GuardId.FINANCIAL_EVIDENCE, statement)).isTrue()
        assertThat(
            GuardLibrary.matches(
                GuardId.FINANCIAL_EVIDENCE,
                "Exciting offer! Get 10% cashback up to Rs 500 on your card. Shop now",
            ),
        ).isFalse()
    }
}
