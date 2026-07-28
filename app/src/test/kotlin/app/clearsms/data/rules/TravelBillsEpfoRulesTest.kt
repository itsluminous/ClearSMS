package app.clearsms.data.rules

import app.clearsms.domain.model.Category
import app.clearsms.domain.model.SubCategory
import app.clearsms.domain.parser.SenderNameResolver
import com.google.common.truth.Truth.assertThat
import kotlinx.serialization.json.Json
import org.junit.Test
import java.io.File

/**
 * Fixture tests for the travel (flight/train), bill-vs-receipt and EPFO
 * rules added from real-corpus analysis. All message bodies are synthetic,
 * reconstructed from corpus message shapes with masked identifiers.
 */
class TravelBillsEpfoRulesTest {
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

    // region U1 — flights

    @Test
    fun `flight booking confirmation with PNR is informational travel`() {
        val result =
            evaluate(
                "INDIGO",
                "Dear Mr Kumar, we are happy to confirm your booking under PNR - ZXCHRQ, " +
                    "10 Jun 26, from PAT to BLR(T1), 6E2345 at 10:30 hrs.",
            )
        assertThat(result?.matchedRuleId).isEqualTo("flight-booking-pnr-01")
        assertThat(result?.category).isEqualTo(Category.INFORMATIONAL)
        assertThat(result?.subCategory).isEqualTo(SubCategory.TRAVEL)
        assertThat(result?.extracted).containsEntry("pnr", "ZXCHRQ")
    }

    @Test
    fun `flight booking confirmation with trailing PNR is informational travel`() {
        val result =
            evaluate(
                "INDIGO",
                "Hello Mr Kumar, we're happy to confirm your booking. PNR - TMQDXY, " +
                    "12 Aug 26, from BLR(T1) to PAT, 6E 234 at 08:15 hrs.",
            )
        assertThat(result?.matchedRuleId).isEqualTo("flight-booking-pnr-02")
        assertThat(result?.extracted).containsEntry("pnr", "TMQDXY")
    }

    @Test
    fun `airline fare sale promo does not match travel rules`() {
        val result =
            evaluate("INDIGO", "Monsoon sale! Fly across India, fares starting low. Hurry, offer ends soon.")
        assertThat(result).isNull()
    }

    @Test
    fun `flight itinerary reminder with PNR is informational travel`() {
        val result =
            evaluate(
                "INDIGO",
                "IndiGo: Dear flyer, your IndiGo PNR is HQBRXE - 6E 1234, 12Aug,PAT-BLR(T1) " +
                    "0915-1200 HRS. For a hassle-free airport experience, please web check-in.",
            )
        assertThat(result?.matchedRuleId).isEqualTo("flight-itinerary-pnr-01")
        assertThat(result?.category).isEqualTo(Category.INFORMATIONAL)
        assertThat(result?.extracted).containsEntry("pnr", "HQBRXE")
    }

    @Test
    fun `web check-in complete notice is informational travel`() {
        val result =
            evaluate(
                "INDIGO",
                "Hello MR Prakash Kumar, your web check-in for flight 6E 234 from BLR to PAT is " +
                    "successfully complete. Your e-boarding pass is now available to download.",
            )
        assertThat(result?.matchedRuleId).isEqualTo("flight-checkin-done-01")
        assertThat(result?.subCategory).isEqualTo(SubCategory.TRAVEL)
    }

    @Test
    fun `baggage belt notice is informational travel`() {
        val result =
            evaluate(
                "AKASAA",
                "Hi Prakash, your checked-in baggage for Akasa Air flight QP 1234 will arrive on Belt no. 12.",
            )
        assertThat(result?.matchedRuleId).isEqualTo("flight-baggage-01")
        assertThat(result?.category).isEqualTo(Category.INFORMATIONAL)
    }

    @Test
    fun `gate change notice is informational travel`() {
        val result =
            evaluate(
                "TRPSRC",
                "TripSource: The gate has changed for your flight to Hyderabad (6E 1234). " +
                    "It is now departing from Terminal 1, Gate 22.",
            )
        assertThat(result?.matchedRuleId).isEqualTo("flight-gate-change-01")
        assertThat(result?.subCategory).isEqualTo(SubCategory.TRAVEL)
    }

    @Test
    fun `flight reschedule notice is informational travel`() {
        val result =
            evaluate(
                "GOFRST",
                "Dear GO FIRST Flyer! Your booking ref:(JCQSGA) (12Nov'26), (G8-123) (BLR-PAT) " +
                    "is rescheduled and will now depart on (13Nov'26) due to operational reasons.",
            )
        assertThat(result?.matchedRuleId).isEqualTo("flight-reschedule-01")
        assertThat(result?.category).isEqualTo(Category.INFORMATIONAL)
    }

    @Test
    fun `airline itinerary modified is informational travel`() {
        val result =
            evaluate(
                "SPICEJ",
                "Your SpiceJet travel from BLR(T1), 20 December 2026, PNR DKQCKB, has been modified. " +
                    "View itinerary on our website.",
            )
        assertThat(result?.matchedRuleId).isEqualTo("flight-itinerary-status-01")
        assertThat(result?.extracted).containsEntry("pnr", "DKQCKB")
    }

    @Test
    fun `airline refund initiated is an important transaction`() {
        val result =
            evaluate(
                "SPICEJ",
                "Dear Ms. Kumari, your request for refund pertaining to SpiceJet PNR SBJQQP has been " +
                    "initiated. Please check your original source of payment within 15 working days.",
            )
        assertThat(result?.matchedRuleId).isEqualTo("flight-refund-initiated-01")
        assertThat(result?.category).isEqualTo(Category.IMPORTANT)
        assertThat(result?.subCategory).isEqualTo(SubCategory.TRANSACTION)
    }

    @Test
    fun `refund marketing blurb does not match the refund rule`() {
        val result =
            evaluate("SPICEJ", "Full refund on cancellations! Book your next SpiceJet trip today and save more.")
        assertThat(result?.matchedRuleId).isNotEqualTo("flight-refund-initiated-01")
    }

    // endregion

    // region U1 — trains

    @Test
    fun `train running late notice is informational travel`() {
        val result =
            evaluate(
                "IRSMSA",
                "The Train is running late by 02:30 hrs from the last destination station. " +
                    "It is likely to make up. Please check exact status from NTES.",
            )
        assertThat(result?.matchedRuleId).isEqualTo("train-running-late-01")
        assertThat(result?.category).isEqualTo(Category.INFORMATIONAL)
        assertThat(result?.subCategory).isEqualTo(SubCategory.TRAVEL)
    }

    @Test
    fun `train cancelled notice is informational travel`() {
        val result =
            evaluate(
                "IRSMSA",
                "Train No 12345 has been cancelled due to unavoidable circumstances. " +
                    "Inconvenience caused to passengers is deeply regretted.",
            )
        assertThat(result?.matchedRuleId).isEqualTo("train-cancelled-01")
        assertThat(result?.category).isEqualTo(Category.INFORMATIONAL)
    }

    @Test
    fun `railmadad reference is informational`() {
        val result =
            evaluate(
                "IRSMSA",
                "Thank you for contacting us. Your RailMadad Reference No. is: 1234567890123. " +
                    "You can track your concern online.",
            )
        assertThat(result?.matchedRuleId).isEqualTo("railmadad-ref-01")
        assertThat(result?.category).isEqualTo(Category.INFORMATIONAL)
    }

    @Test
    fun `railway service upsell does not match travel rules`() {
        val result =
            evaluate("IRSMSA", "Indian Railways has restarted linen supply in trains in a phased manner.")
        assertThat(result).isNull()
    }

    // endregion

    // region U2 — bills vs receipts

    @Test
    fun `bill generated with due date is an important bill`() {
        val result =
            evaluate(
                "AIRBIL",
                "Hi Prakash, Bill for your Airtel Mobile 9812345678 dated 05-AUG-2026 has been generated. " +
                    "Amount to be paid: Rs 599.90 Due Date: 23-AUG-2026",
            )
        assertThat(result?.matchedRuleId).isEqualTo("airtel-bill-generated-01")
        assertThat(result?.category).isEqualTo(Category.IMPORTANT)
        assertThat(result?.subCategory).isEqualTo(SubCategory.BILL)
        assertThat(result?.extracted).containsEntry("amount", "599.90")
    }

    @Test
    fun `bill generated with nothing payable is informational`() {
        val result =
            evaluate(
                "AIRBIL",
                "Hi Prakash, Bill for your Airtel Mobile 9812345678 dated 05-OCT-2026 has been " +
                    "generated and there is no payable amount this cycle.",
            )
        assertThat(result?.matchedRuleId).isEqualTo("airtel-bill-nodue-01")
        assertThat(result?.category).isEqualTo(Category.INFORMATIONAL)
    }

    @Test
    fun `bill details with total due is an important bill`() {
        val result =
            evaluate(
                "AIRBIL",
                "Details of bill dated 05-JUL-2026 for your Airtel Mobile no.9812345678 " +
                    "Total Due: Rs 649.00 Due Date: 23-AUG-2026 Previous Due: Rs 0.00",
            )
        assertThat(result?.matchedRuleId).isEqualTo("airtel-bill-details-01")
        assertThat(result?.subCategory).isEqualTo(SubCategory.BILL)
    }

    @Test
    fun `bill due for payment reminder is an important bill`() {
        val result =
            evaluate(
                "AIRBIL",
                "Hi, bill dated 05-AUG-26 for your Airtel number 9812345678 is due for payment on " +
                    "23-SEP-26. The amount to be paid on or before the due date is Rs 599.",
            )
        assertThat(result?.matchedRuleId).isEqualTo("airtel-bill-due-02")
        assertThat(result?.subCategory).isEqualTo(SubCategory.BILL)
    }

    @Test
    fun `payment receipt is an important transaction and never a bill reminder`() {
        val result =
            evaluate(
                "AIRBIL",
                "Hi Prakash Kumar, We have received a payment of Rs. 599.90 for your Airtel account. " +
                    "To download the payment receipt, visit the app.",
            )
        assertThat(result?.matchedRuleId).isEqualTo("airtel-payment-received-01")
        assertThat(result?.category).isEqualTo(Category.IMPORTANT)
        assertThat(result?.subCategory).isEqualTo(SubCategory.TRANSACTION)
        assertThat(result?.subCategory).isNotEqualTo(SubCategory.BILL)
        assertThat(result?.extracted).containsEntry("amount", "599.90")
    }

    @Test
    fun `payment nag without a receipt does not match the receipt rule`() {
        val result =
            evaluate("AIRBIL", "Recharge now to keep enjoying unlimited calls! Best plans start at Rs 199.")
        assertThat(result?.matchedRuleId).isNotEqualTo("airtel-payment-received-01")
        assertThat(result?.subCategory).isNotEqualTo(SubCategory.TRANSACTION)
    }

    // endregion

    // region U3 — EPFO

    @Test
    fun `epf passbook contribution is an important investment deposit`() {
        val result =
            evaluate(
                "EPFOHO",
                "Dear XXXXXXXX1234,your passbook balance against TNMAS00123456789001234 is Rs. 92,150/-. " +
                    "Contribution of Rs. 12,345/- for due month Aug-26 has been received.",
            )
        assertThat(result?.matchedRuleId).isEqualTo("epfo-passbook-contribution-01")
        assertThat(result?.category).isEqualTo(Category.IMPORTANT)
        assertThat(result?.subCategory).isEqualTo(SubCategory.INVESTMENT)
        assertThat(result?.extracted).containsEntry("amount", "12,345")
        assertThat(result?.extracted).containsEntry("account_last4", "1234")
        assertThat(result?.extracted).containsEntry("balance", "92,150")
        assertThat(result?.extracted).containsEntry("bank", "EPFO")
    }

    @Test
    fun `epf passbook contribution with masked member id extracts the visible tail`() {
        val result =
            evaluate(
                "EPFOHO",
                "Dear XXXXXXXX1234, your passbook balance against BGBNG**************5678 is Rs. 1,92,150/-. " +
                    "Contribution of Rs. 9,876/- for due month Sep-26 has been received.",
            )
        assertThat(result?.matchedRuleId).isEqualTo("epfo-passbook-contribution-01")
        assertThat(result?.extracted).containsEntry("account_last4", "5678")
    }

    @Test
    fun `epf balance notice without contribution is informational`() {
        val result =
            evaluate(
                "EPFOHO",
                "Dear Member, your passbook balance against TNMAS00123456789001234 is Rs. 92,150/- as on date.",
            )
        assertThat(result?.matchedRuleId).isEqualTo("epfo-passbook-balance-01")
        assertThat(result?.category).isEqualTo(Category.INFORMATIONAL)
    }

    @Test
    fun `epf interest credit is important but fabricates no transaction type`() {
        val result =
            evaluate(
                "EPFOHO",
                "PF interest of 4,321 for 2025-26 credited to your UAN 100123456789 " +
                    "(APHYD00123456789001234) The CB on 31MAR2026 is 96,471 - EPFO",
            )
        assertThat(result?.matchedRuleId).isEqualTo("epfo-interest-credited-01")
        assertThat(result?.category).isEqualTo(Category.IMPORTANT)
        assertThat(result?.extracted).containsEntry("amount", "4,321")
        assertThat(result?.extracted).doesNotContainKey("type")
    }

    @Test
    fun `epf claim under process is informational`() {
        val result =
            evaluate(
                "EPFOHO",
                "Dear Sir/Madam, your Claim Id PYKRP123456789012 is under process and will be settled shortly. - EPFO",
            )
        assertThat(result?.matchedRuleId).isEqualTo("epfo-claim-status-01")
        assertThat(result?.category).isEqualTo(Category.INFORMATIONAL)
        assertThat(result?.subCategory).isEqualTo(SubCategory.GOVERNMENT)
    }

    @Test
    fun `epf claim settled is important`() {
        val result =
            evaluate(
                "EPFOHO",
                "Dear Member, your claim for PF withdrawal has been settled. Amount will reflect in your bank account.",
            )
        assertThat(result?.matchedRuleId).isEqualTo("epfo-claim-settled-01")
        assertThat(result?.category).isEqualTo(Category.IMPORTANT)
    }

    @Test
    fun `uan activation is informational`() {
        val result =
            evaluate("EPFOHO", "Dear Member, your UAN 100123456789 has been activated successfully on the portal.")
        assertThat(result?.matchedRuleId).isEqualTo("epfo-uan-activated-01")
        assertThat(result?.category).isEqualTo(Category.INFORMATIONAL)
    }

    @Test
    fun `uan password change notice is informational`() {
        val result =
            evaluate(
                "EPFOHO",
                "Dear Member, Password for your UAN 100123456789 has been changed successfully. " +
                    "If not done by you, then change password immediately.",
            )
        assertThat(result?.matchedRuleId).isEqualTo("epfo-uan-security-01")
        assertThat(result?.category).isEqualTo(Category.INFORMATIONAL)
    }

    @Test
    fun `epfo channel promo does not match any rule`() {
        val result =
            evaluate("EPFOHO", "EPFO YouTube is now @officialepfo. Please join to watch informative videos.")
        assertThat(result).isNull()
    }

    @Test
    fun `epfo is a plausible issuer so contributions can claim an account`() {
        assertThat(SenderNameResolver.isPlausibleIssuer("EPFO")).isTrue()
    }

    // endregion
}
