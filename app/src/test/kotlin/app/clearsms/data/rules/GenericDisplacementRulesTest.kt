package app.clearsms.data.rules

import app.clearsms.domain.model.Category
import app.clearsms.domain.model.SubCategory
import com.google.common.truth.Truth.assertThat
import kotlinx.serialization.json.Json
import org.junit.Test
import java.io.File

/**
 * Coverage tests for the sender-specific rules that displace the generic-*
 * fallbacks (banks, cards, wallets, couriers). Every message here is
 * SYNTHETIC — patterns were derived from message *shapes*, and these tests
 * assert each representative rule matches its intended shape (with capture
 * order) and rejects a near-miss, while the generic safety net stays intact.
 */
class GenericDisplacementRulesTest {
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
    fun `hdfc narrated debit outranks the generic balance fallback`() {
        val result =
            evaluate(
                "VM-HDFCBK-S",
                "UPDATE: INR 4,510.00 debited from HDFC Bank XX8811 on 12-JUL-26. " +
                    "Info: ACH D- Example Clearing Corp. Avl bal INR 90,000.00",
            )
        assertThat(result?.matchedRuleId).isEqualTo("hdfc-debit-info-01")
        assertThat(result?.category).isEqualTo(Category.IMPORTANT)
        assertThat(result?.subCategory).isEqualTo(SubCategory.TRANSACTION)
        assertThat(result?.extracted?.get("amount")).isEqualTo("4,510.00")
        assertThat(result?.extracted?.get("account_last4")).isEqualTo("8811")
        assertThat(result?.extracted?.get("merchant")).isEqualTo("ACH D- Example Clearing Corp")
        assertThat(result?.extracted?.get("type")).isEqualTo("debit")
    }

    @Test
    fun `hdfc card transaction otp captures code amount merchant and card`() {
        val result =
            evaluate(
                "VM-HDFCBK-S",
                "OTP is 123456 for txn of INR 999.00 at SAMPLE STORE on HDFC Bank " +
                    "card ending 4321. Valid till 10:30. Do not share OTP",
            )
        assertThat(result?.matchedRuleId).isEqualTo("hdfc-otp-txn-01")
        assertThat(result?.category).isEqualTo(Category.OTP)
        assertThat(result?.extracted?.get("otp_code")).isEqualTo("123456")
        assertThat(result?.extracted?.get("amount")).isEqualTo("999.00")
        assertThat(result?.extracted?.get("merchant")).isEqualTo("SAMPLE STORE")
        assertThat(result?.extracted?.get("account_last4")).isEqualTo("4321")
    }

    @Test
    fun `hdfc otp rule does not fire for other banks`() {
        val result =
            evaluate(
                "VM-OTRBNK-S",
                "OTP is 123456 for txn of INR 999.00 at SAMPLE STORE on OTHER Bank " +
                    "card ending 4321. Valid till 10:30. Do not share OTP",
            )
        assertThat(result?.matchedRuleId).isNotEqualTo("hdfc-otp-txn-01")
    }

    @Test
    fun `icici card spend captures merchant before the available limit`() {
        val result =
            evaluate(
                "AD-ICICIT-S",
                "INR 2,499.00 spent using ICICI Bank Card XX9002 on 15-Jun-26 on " +
                    "SAMPLE MART. Avl Limit: INR 1,00,000.00. If not you, call 18001234",
            )
        assertThat(result?.matchedRuleId).isEqualTo("icici-card-spent-01")
        assertThat(result?.subCategory).isEqualTo(SubCategory.TRANSACTION)
        assertThat(result?.extracted?.get("amount")).isEqualTo("2,499.00")
        assertThat(result?.extracted?.get("account_last4")).isEqualTo("9002")
        assertThat(result?.extracted?.get("merchant")).isEqualTo("SAMPLE MART")
        assertThat(result?.extracted?.get("type")).isEqualTo("debit")
    }

    @Test
    fun `icici statement is a bill not a debit transaction`() {
        val result =
            evaluate(
                "AD-ICICIB-S",
                "Total Due INR 12345.60 & Min Due INR 620 to be paid by 03-Oct-26 on " +
                    "ICICI Bank Credit Card XX9002. Non-payment attracts charges.",
            )
        assertThat(result?.matchedRuleId).isEqualTo("icici-cc-stmt-01")
        assertThat(result?.subCategory).isEqualTo(SubCategory.BILL)
        assertThat(result?.extracted?.get("total_due")).isEqualTo("12345.60")
        assertThat(result?.extracted?.get("min_due")).isEqualTo("620")
        assertThat(result?.extracted?.get("due_date")).isEqualTo("03-Oct-26")
        assertThat(result?.extracted?.get("account_last4")).isEqualTo("9002")
    }

    @Test
    fun `icici statement rule rejects a shape without a minimum due`() {
        val result =
            evaluate(
                "AD-ICICIB-S",
                "Total Due INR 12345.60 to be paid by 03-Oct-26 on ICICI Bank " +
                    "Credit Card XX9002.",
            )
        assertThat(result?.matchedRuleId).isNotEqualTo("icici-cc-stmt-01")
    }

    @Test
    fun `sbi upi credit captures account and amount`() {
        val result =
            evaluate(
                "CP-SBIUPI-S",
                "Dear SBI UPI User, ur A/cX5566 credited by Rs151 on 09Jul26 by (Ref no 5551234)",
            )
        assertThat(result?.matchedRuleId).isEqualTo("sbi-upi-credit-01")
        assertThat(result?.extracted?.get("account_last4")).isEqualTo("5566")
        assertThat(result?.extracted?.get("amount")).isEqualTo("151")
        assertThat(result?.extracted?.get("type")).isEqualTo("credit")
    }

    @Test
    fun `sbi upi credit rule rejects a debit wording`() {
        val result =
            evaluate(
                "CP-SBIUPI-S",
                "Dear SBI UPI User, ur A/cX5566 debited by Rs151 on 09Jul26 by (Ref no 5551234)",
            )
        assertThat(result?.matchedRuleId).isNotEqualTo("sbi-upi-credit-01")
    }

    @Test
    fun `citi card spend captures merchant before the limit`() {
        val result =
            evaluate(
                "VK-CITIBA-S",
                "Rs. 1,250.00 spent on card 8899 on 14-FEB-26 at SAMPLE DINER. " +
                    "Limit available=Rs. 88,000.00.If not done by you, click example",
            )
        assertThat(result?.matchedRuleId).isEqualTo("citi-card-spent-01")
        assertThat(result?.extracted?.get("amount")).isEqualTo("1,250.00")
        assertThat(result?.extracted?.get("account_last4")).isEqualTo("8899")
        assertThat(result?.extracted?.get("merchant")).isEqualTo("SAMPLE DINER")
    }

    @Test
    fun `axis cashback is a credit transaction`() {
        val result =
            evaluate(
                "AX-AXISBK-S",
                "Congratulations! Cashback of INR 150 has been credited to your " +
                    "Axis Bank Rewards Visa Credit Card XX7001 towards your last month spends - Axis Bank",
            )
        assertThat(result?.matchedRuleId).isEqualTo("axis-cashback-01")
        assertThat(result?.extracted?.get("amount")).isEqualTo("150")
        assertThat(result?.extracted?.get("account_last4")).isEqualTo("7001")
        assertThat(result?.extracted?.get("type")).isEqualTo("credit")
    }

    @Test
    fun `payzapp card spend captures merchant and balance`() {
        val result =
            evaluate(
                "VM-PAYZAP-S",
                "UPDATE: Rs.320.00 was spent on your PayZapp card xx5522 at Sample BBPS. " +
                    "Available balance: Rs.12.50. Not you? Call 18002587",
            )
        assertThat(result?.matchedRuleId).isEqualTo("payzapp-card-spend-01")
        assertThat(result?.extracted?.get("amount")).isEqualTo("320.00")
        assertThat(result?.extracted?.get("account_last4")).isEqualTo("5522")
        assertThat(result?.extracted?.get("merchant")).isEqualTo("Sample BBPS")
        assertThat(result?.extracted?.get("balance")).isEqualTo("12.50")
    }

    @Test
    fun `paytm wallet payment captures the payee`() {
        val result =
            evaluate(
                "VM-IPAYTM-S",
                "Paid Rs. 240 to samplegrocer with Paytm Ref: 5550001234. For details visit example",
            )
        assertThat(result?.matchedRuleId).isEqualTo("paytm-wallet-paid-01")
        assertThat(result?.extracted?.get("amount")).isEqualTo("240")
        assertThat(result?.extracted?.get("merchant")).isEqualTo("samplegrocer")
        assertThat(result?.extracted?.get("type")).isEqualTo("debit")
    }

    @Test
    fun `cred payment reports the issuing bank as a credit`() {
        val result =
            evaluate(
                "JD-CREDIN-S",
                "Payment of INR 5,000 was received for your HDFC Bank credit card " +
                    "1111-22XX-XXXX-3333 on 05-Dec-2026 and you have earned 1,000 CRED coins. " +
                    "Your order id is SAMPLE1",
            )
        assertThat(result?.matchedRuleId).isEqualTo("cred-cc-payment-01")
        assertThat(result?.extracted?.get("amount")).isEqualTo("5,000")
        assertThat(result?.extracted?.get("bank")).isEqualTo("HDFC Bank")
        assertThat(result?.extracted?.get("type")).isEqualTo("credit")
    }

    @Test
    fun `amazon otp matches from any sender`() {
        val result =
            evaluate(
                "+915550001111",
                "123456 is your Amazon OTP. Do not share it with anyone.",
            )
        assertThat(result?.matchedRuleId).isEqualTo("amazon-otp-01")
        assertThat(result?.category).isEqualTo(Category.OTP)
        assertThat(result?.extracted?.get("otp_code")).isEqualTo("123456")
    }

    @Test
    fun `bse trade confirmation is an investment not an otp`() {
        val result =
            evaluate(
                "VM-BSELTD-S",
                "BSE Trade Confirmation Client Code 1234567890 - Broker 9999 - " +
                    "EQ Value Rs 55000.00 - FNO Value Rs 0.00 - Dated 09-07-2026",
            )
        assertThat(result?.matchedRuleId).isEqualTo("bse-trade-confirm-01")
        assertThat(result?.category).isEqualTo(Category.IMPORTANT)
        assertThat(result?.subCategory).isEqualTo(SubCategory.INVESTMENT)
    }

    @Test
    fun `flipkart refund is a credit transaction`() {
        val result =
            evaluate(
                "VM-FLPKRT-S",
                "Refund Processed: Refund of Rs. 89.00 for your Flipkart order of " +
                    "Sample Item is successfully processed to SAMPLE card ending 0001",
            )
        assertThat(result?.matchedRuleId).isEqualTo("flipkart-refund-01")
        assertThat(result?.extracted?.get("amount")).isEqualTo("89.00")
        assertThat(result?.extracted?.get("type")).isEqualTo("credit")
    }

    @Test
    fun `india post article tracking is a delivery update`() {
        val result =
            evaluate(
                "VM-INPOST-S",
                "Article JQ123456789IN delivered on 09/07/2026 to SAMPLE NAME. " +
                    "Please share delivery feedback at example",
            )
        assertThat(result?.matchedRuleId).isEqualTo("indiapost-article-01")
        assertThat(result?.subCategory).isEqualTo(SubCategory.DELIVERY)
    }

    @Test
    fun `generic debit safety net still catches unknown banks`() {
        val result =
            evaluate(
                "AB-XYZBNK-S",
                "INR 500.00 debited from a/c no. XX1234 towards purchase",
            )
        assertThat(result?.matchedRuleId).isEqualTo("generic-debit-01")
        assertThat(result?.subCategory).isEqualTo(SubCategory.TRANSACTION)
    }
}
