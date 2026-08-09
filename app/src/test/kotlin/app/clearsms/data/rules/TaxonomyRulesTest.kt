package app.clearsms.data.rules

import app.clearsms.domain.model.Category
import app.clearsms.domain.model.SubCategory
import com.google.common.truth.Truth.assertThat
import kotlinx.serialization.json.Json
import org.junit.Test
import java.io.File

/**
 * Fixture tests for the informational / investment / recharge taxonomy rules
 * added from real-corpus analysis. All message bodies are synthetic,
 * reconstructed from message shapes with masked identifiers.
 */
class TaxonomyRulesTest {
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

    // region T3b - INFORMATIONAL

    @Test
    fun `cibil score access notice is informational`() {
        val result =
            evaluate("CIBIL", "Your CIBIL Score & Report was checked by FEDERAL BANK ECN:12345 on 2026-06-15. -CIBIL")
        assertThat(result?.matchedRuleId).isEqualTo("cibil-score-check-01")
        assertThat(result?.category).isEqualTo(Category.IMPORTANT)
    }

    @Test
    fun `flight seat assignment is informational travel`() {
        val result =
            evaluate("AKASAA", "Hi, you've been assigned seat 12D for your upcoming Akasa Air flight. Have a pleasant journey.")
        assertThat(result?.matchedRuleId).isEqualTo("flight-seat-01")
        assertThat(result?.category).isEqualTo(Category.IMPORTANT)
        assertThat(result?.subCategory).isEqualTo(SubCategory.TRAVEL)
    }

    @Test
    fun `hospital token number is informational appointment`() {
        val result =
            evaluate("SAKRAH", "NEROW 005 is your token number for Walk-In, please wait for your turn. -SAKRA WORLD HOSPITAL")
        assertThat(result?.matchedRuleId).isEqualTo("hospital-token-01")
        assertThat(result?.category).isEqualTo(Category.IMPORTANT)
        assertThat(result?.subCategory).isEqualTo(SubCategory.APPOINTMENT)
    }

    @Test
    fun `exchange traded value notice is informational investment`() {
        val result =
            evaluate("NSESMS", "Dear CTWXXXXX2R, Your traded value for 29-JUN-26 CM Rs 12050.88 combined. -National Stock Exchange")
        assertThat(result?.matchedRuleId).isEqualTo("nse-traded-value-01")
        assertThat(result?.category).isEqualTo(Category.IMPORTANT)
        assertThat(result?.subCategory).isEqualTo(SubCategory.INVESTMENT)
        assertThat(result?.extracted).doesNotContainKey("amount")
    }

    @Test
    fun `broker fund balance statement is informational investment`() {
        val result =
            evaluate("NSETRA", "BROKER LTD on 26-07-26 reported your Fund bal Rs.512.00 & Securities bal 12 - NSE")
        assertThat(result?.matchedRuleId).isEqualTo("nse-broker-balance-01")
        assertThat(result?.category).isEqualTo(Category.IMPORTANT)
    }

    @Test
    fun `train chart prepared notice is informational travel`() {
        val result =
            evaluate("IRSMSA", "PNR-1234567890 Trn:12345 Frm ABC to DEF Cls:3A P1-B2,34 Chart Prepared")
        assertThat(result?.matchedRuleId).isEqualTo("train-pnr-status-01")
        assertThat(result?.category).isEqualTo(Category.IMPORTANT)
        assertThat(result?.subCategory).isEqualTo(SubCategory.TRAVEL)
    }

    @Test
    fun `upi mandate created is informational and extracts no transaction`() {
        val result =
            evaluate(
                "TATANU",
                "Hi, UPI Autopay Mandate with ASPRESENTED frequency is successfully created towards " +
                    "Amazon India from 06/07/26 to 06/07/31 for Rs 1499.00, Your UMN is abc@okhdfc - Team Tata Neu.",
            )
        assertThat(result?.matchedRuleId).isEqualTo("upi-mandate-created-01")
        assertThat(result?.category).isEqualTo(Category.IMPORTANT)
        assertThat(result?.extracted).doesNotContainKey("amount")
    }

    @Test
    fun `upi mandate cancellation is informational`() {
        val result =
            evaluate(
                "TATANU",
                "Hi, you have successfully cancelled the scheduled ASPRESENTED payment of amount " +
                    "MAX Rs. 1499.00 towards Amazon India. - Team Tata Neu",
            )
        assertThat(result?.matchedRuleId).isEqualTo("upi-mandate-cancelled-01")
        assertThat(result?.category).isEqualTo(Category.IMPORTANT)
    }

    // endregion

    // region T3c - investments (SIP / NPS)

    @Test
    fun `sip purchase amount-first shape is an investment debit titled by fund`() {
        val result =
            evaluate(
                "IPRUMF",
                "Dear Investor, Your SIP Purchase of Rs.99,995.00 in Folio 14984542 in Focused Equity Fund " +
                    "- DP Growth for 901.587 units has been processed for NAV of Rs.110.91 on 20-Jul-2026 - IPRUMF",
            )
        assertThat(result?.matchedRuleId).isEqualTo("mf-sip-purchase-01")
        assertThat(result?.category).isEqualTo(Category.IMPORTANT)
        assertThat(result?.subCategory).isEqualTo(SubCategory.MUTUAL_FUND)
        assertThat(result?.extracted?.get("amount")).isEqualTo("99,995.00")
        assertThat(result?.extracted?.get("type")).isEqualTo("debit")
        assertThat(result?.extracted?.get("merchant")).isEqualTo("Focused Equity Fund")
    }

    @Test
    fun `sip purchase fund-first shape is titled by fund without plan suffix`() {
        val result =
            evaluate(
                "HDFCMF",
                "Your SIP Purchase in Folio 17766840/10 under HDFC Flexi Cap Fund-DP-Growth for Rs. 79,996.00 " +
                    "has been processed at the NAV of 2240.661 for 35.702 units and 10-Jul-2026. Thank you HDFCMF",
            )
        assertThat(result?.matchedRuleId).isEqualTo("mf-sip-purchase-02")
        assertThat(result?.extracted?.get("merchant")).isEqualTo("HDFC Flexi Cap Fund")
        assertThat(result?.extracted?.get("amount")).isEqualTo("79,996.00")
        assertThat(result?.extracted?.get("type")).isEqualTo("debit")
    }

    @Test
    fun `sip units-allotted shape is an investment debit titled by fund`() {
        val result =
            evaluate(
                "CRMF",
                "Your SIP - Rs.10999.45 in Folio XXXXXXX6073 - CR Elss Tax Saver Fund - Direct Growth - " +
                    "54.388 units allotted at NAV of Rs.202.24 on 10/07/2026 - CRMF",
            )
        assertThat(result?.matchedRuleId).isEqualTo("mf-sip-allotment-01")
        assertThat(result?.extracted?.get("merchant")).isEqualTo("CR Elss Tax Saver Fund")
        assertThat(result?.extracted?.get("amount")).isEqualTo("10999.45")
        assertThat(result?.extracted?.get("type")).isEqualTo("debit")
    }

    @Test
    fun `mf redemption is not treated as a purchase by the catch-all`() {
        val result =
            evaluate(
                "IPRUMF",
                "Dear Investor, your redemption purchase request of Rs.5,000.00 for 10.5 units at NAV is processed.",
            )
        assertThat(result?.matchedRuleId).isNotEqualTo("mf-purchase-generic-01")
    }

    @Test
    fun `nps contribution is an investment credit with the pran tail`() {
        val result =
            evaluate("PTNNPS", "PRAN XX8227: Units for (APR-2026) contribution of Rs.44,236.00 credited with NAV of 07/05/26 -Protean")
        assertThat(result?.matchedRuleId).isEqualTo("nps-contribution-01")
        assertThat(result?.category).isEqualTo(Category.IMPORTANT)
        assertThat(result?.subCategory).isEqualTo(SubCategory.INVESTMENT)
        assertThat(result?.extracted?.get("amount")).isEqualTo("44,236.00")
        // Units credited INTO the retirement account are money received -
        // an employer contribution never debits a tracked bank account.
        assertThat(result?.extracted?.get("type")).isEqualTo("credit")
        assertThat(result?.extracted?.get("merchant")).isEqualTo("NPS")
        assertThat(result?.extracted?.get("account_last4")).isEqualTo("8227")
    }

    @Test
    fun `nps investment value statement is informational and never a transaction`() {
        val result =
            evaluate("PTNNPS", "Investment value in Tier I (PRANXX8227) as on 30.06.2026 is Rs 10,51,328.93. -Protean")
        assertThat(result?.matchedRuleId).isEqualTo("nps-balance-01")
        assertThat(result?.category).isEqualTo(Category.IMPORTANT)
        assertThat(result?.extracted).doesNotContainKey("amount")
        assertThat(result?.extracted).doesNotContainKey("type")
    }

    // endregion

    // region T3d/T3e - recharge and OTP

    @Test
    fun `prepaid recharge success is an important recharge debit`() {
        val result =
            evaluate("VICARE", "Hi, Your Prepaid recharge of Rs. 1198.0 is success against Order Id 7481038541423177728.")
        assertThat(result?.category).isEqualTo(Category.IMPORTANT)
        assertThat(result?.subCategory).isEqualTo(SubCategory.RECHARGE)
        assertThat(result?.extracted?.get("amount")).isEqualTo("1198.0")
        assertThat(result?.extracted?.get("type")).isEqualTo("debit")
    }

    @Test
    fun `recharge nag without success is not a recharge transaction`() {
        val result = evaluate("VICARE", "Recharge now & get unlimited calls! Best plans starting Rs.199. Hurry!")
        assertThat(result?.subCategory).isNotEqualTo(SubCategory.RECHARGE)
        assertThat(result?.category).isEqualTo(Category.PROMOTIONAL)
    }

    @Test
    fun `act fibernet otp is otp with the code extracted`() {
        val result =
            evaluate("ACTBRD", "Your OTP is: 573372. Thank You for your interest in Act Fibernet. The OTP will be valid for 5 minutes.")
        assertThat(result?.matchedRuleId).isEqualTo("act-otp-01")
        assertThat(result?.category).isEqualTo(Category.OTP)
        assertThat(result?.extracted?.get("otp_code")).isEqualTo("573372")
    }

    // endregion
}
