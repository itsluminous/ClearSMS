package app.clearsms.data.rules

import app.clearsms.domain.model.Category
import app.clearsms.domain.model.SubCategory
import com.google.common.truth.Truth.assertThat
import kotlinx.serialization.json.Json
import org.junit.Test
import java.io.File

/**
 * Coverage tests for the rules added from real-corpus analysis (wallets,
 * travel, health, utilities, telecom services, IT alerts, refunds and the
 * branded-OTP variant). Every message here is SYNTHETIC — the patterns were
 * derived from message *shapes*, and these tests assert each representative
 * new pattern matches its intended shape and rejects a near-miss.
 */
class DeviceCorpusRulesTest {
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

    @Test
    fun `bundled asset parses and every pattern compiles`() {
        assertThat(rules).isNotEmpty()
        for (rule in rules) {
            rule.match.senderPattern?.let { Regex(it) }
            rule.match.bodyPattern?.let { Regex(it) }
        }
    }

    @Test
    fun `meal card wallet spend is a debit transaction`() {
        val result =
            evaluate(
                "VD-PLUXEE-S",
                "Rs. 150.00 spent from Pluxee Meal Card wallet, card no. on 12-05-2026 " +
                    "10:11:12 at SAMPLE CAFE CITY. Avl bal Rs.900.00. Not you call 08012345678",
            )
        assertThat(result?.matchedRuleId).isEqualTo("pluxee-debit-01")
        assertThat(result?.category).isEqualTo(Category.IMPORTANT)
        assertThat(result?.subCategory).isEqualTo(SubCategory.TRANSACTION)
        assertThat(result?.extracted?.get("amount")).isEqualTo("150.00")
        assertThat(result?.extracted?.get("type")).isEqualTo("debit")
    }

    @Test
    fun `meal card service notice is not a transaction`() {
        val result = evaluate("VD-PLUXEE-S", "Meal benefit linked to your Pluxee card is now active.")
        assertThat(result?.matchedRuleId).isEqualTo("pluxee-info-01")
        assertThat(result?.subCategory).isNotEqualTo(SubCategory.TRANSACTION)
    }

    @Test
    fun `reimbursement wallet credit is a credit transaction`() {
        val result =
            evaluate(
                "VD-PLUXEE-S",
                "Rs. 1200.00 is credited in your Reimbursement Wallet against your claim.",
            )
        assertThat(result?.matchedRuleId).isEqualTo("pluxee-credit-01")
        assertThat(result?.extracted?.get("type")).isEqualTo("credit")
    }

    @Test
    fun `train booking confirmation extracts the pnr`() {
        val result =
            evaluate(
                "VM-IRCTCI-S",
                "PNR:1234567890,TRN:12345,DOJ:01-01-26,SL,ABCD-EFGH,DP:N.A.," +
                    "Boarding at ABCD only, PASSENGER+1, Fare:500,C Fee:11.8+PG -IRCTC",
            )
        assertThat(result?.matchedRuleId).isEqualTo("irctc-booking-01")
        assertThat(result?.category).isEqualTo(Category.IMPORTANT)
        assertThat(result?.extracted?.get("pnr")).isEqualTo("1234567890")
    }

    @Test
    fun `train ticket cancellation outranks the booking rule`() {
        val result =
            evaluate(
                "VM-IRCTCI-S",
                "PNR 1234567890 ticket cancelled. Amt 500 will be refunded within 7 days.",
            )
        assertThat(result?.matchedRuleId).isEqualTo("irctc-cancel-01")
    }

    @Test
    fun `wallet balance payment is a debit`() {
        val result =
            evaluate(
                "AX-AMZPAY",
                "Payment of Rs 249.00 using Amazon Pay balance is successful at Amazon.in. " +
                    "Updated Balance: 100.00.",
            )
        assertThat(result?.matchedRuleId).isEqualTo("amazonpay-debit-01")
        assertThat(result?.extracted?.get("amount")).isEqualTo("249.00")
        assertThat(result?.extracted?.get("type")).isEqualTo("debit")
    }

    @Test
    fun `branded otp with digits first is extracted`() {
        val result = evaluate("AX-SAMPLE", "482913 is your Acme OTP. Do not share it with anyone.")
        assertThat(result?.matchedRuleId).isEqualTo("generic-otp-05")
        assertThat(result?.category).isEqualTo(Category.OTP)
        assertThat(result?.extracted?.get("otp_code")).isEqualTo("482913")
    }

    @Test
    fun `digits followed by a non-otp noun are not an otp`() {
        val result = evaluate("AX-SAMPLE", "482913 is your Acme order number. Track it soon.")
        assertThat(result?.category).isNotEqualTo(Category.OTP)
    }

    @Test
    fun `number porting upc notice is important`() {
        val result =
            evaluate(
                "1901",
                "The UPC for your mobile no. 9876543210 is V1234567, valid upto 01-01-26 " +
                    "10:00:00. Do not share this UPC with anyone.",
            )
        assertThat(result?.matchedRuleId).isEqualTo("telecom-porting-01")
        assertThat(result?.category).isEqualTo(Category.IMPORTANT)
    }

    @Test
    fun `a support request is not a port request`() {
        val result = evaluate("AX-SAMPLE", "Your support request has been received by our team.")
        assertThat(result?.matchedRuleId).isNotEqualTo("telecom-porting-01")
    }

    @Test
    fun `electricity bill with consumer number is a bill`() {
        val result =
            evaluate(
                "VD-BILLER",
                "New Electricity Bill for Consumer No 1234567890 is ready. Amount Rs 2100. " +
                    "Due on 15th Feb 2026. Pay online.",
            )
        assertThat(result?.matchedRuleId).isEqualTo("electricity-bill-01")
        assertThat(result?.subCategory).isEqualTo(SubCategory.BILL)
        assertThat(result?.extracted?.get("amount")).isEqualTo("2100")
    }

    @Test
    fun `refund notification is a credit transaction`() {
        val result =
            evaluate(
                "AX-GROCER",
                "Refund Initiated: We have initiated a refund of Rs.150.00 for your order " +
                    "of Sample Item, 1kg. Please check your email for more details.",
            )
        assertThat(result?.matchedRuleId).isEqualTo("generic-refund-01")
        assertThat(result?.extracted?.get("amount")).isEqualTo("150.00")
        assertThat(result?.extracted?.get("type")).isEqualTo("credit")
    }

    @Test
    fun `refund mention without an amount does not match the refund rule`() {
        val result = evaluate("AX-GROCER", "Refund policy details are available on our website FAQ page.")
        assertThat(result?.matchedRuleId).isNotEqualTo("generic-refund-01")
    }

    @Test
    fun `wallet paid-to message is a debit`() {
        val result = evaluate("VD-WALLET", "Paid Rs. 320.50 to Sample Store with Wallet Ref: 12345678901.")
        assertThat(result?.matchedRuleId).isEqualTo("generic-debit-03")
        assertThat(result?.extracted?.get("amount")).isEqualTo("320.50")
        assertThat(result?.extracted?.get("type")).isEqualTo("debit")
    }

    @Test
    fun `monitoring pager alert is important`() {
        val result =
            evaluate(
                "503999",
                "12345: Splunk Alert: Nightly job failed - The alert condition was triggered. Runbook: internal",
            )
        assertThat(result?.matchedRuleId).isEqualTo("generic-itops-alert-01")
        assertThat(result?.category).isEqualTo(Category.IMPORTANT)
    }

    @Test
    fun `kyc registry access alert is government`() {
        val result =
            evaluate(
                "AD-CKYCR",
                "Dear customer, your CKYCRR record bearing reference 12345678901234 was " +
                    "fetched by SAMPLE BANK on 01/01/2026.",
            )
        assertThat(result?.matchedRuleId).isEqualTo("cersai-ckyc-01")
        assertThat(result?.subCategory).isEqualTo(SubCategory.GOVERNMENT)
    }

    @Test
    fun `network survey blast is promotional`() {
        val result =
            evaluate(
                "59999",
                "Based on your network experience with us, how likely are you to recommend " +
                    "us to your friends & family? Text a score from 0 to 10.",
            )
        assertThat(result?.matchedRuleId).isEqualTo("telecom-survey-01")
        assertThat(result?.category).isEqualTo(Category.PROMOTIONAL)
    }

    @Test
    fun `recharge confirmation is extracted`() {
        val result =
            evaluate(
                "AX-RECHRG",
                "Recharge of Rs 19.00 is successful for your Mobile on 01-01-2026 at " +
                    "10:00AM Transaction ID 123456789.",
            )
        assertThat(result?.matchedRuleId).isEqualTo("generic-recharge-01")
        assertThat(result?.subCategory).isEqualTo(SubCategory.RECHARGE)
        assertThat(result?.extracted?.get("amount")).isEqualTo("19.00")
    }
}
