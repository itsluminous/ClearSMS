package app.clearsms.data.rules

import app.clearsms.domain.model.Category
import app.clearsms.domain.model.SubCategory
import app.clearsms.domain.model.amount
import app.clearsms.domain.model.date
import com.google.common.truth.Truth.assertThat
import kotlinx.serialization.json.Json
import org.junit.Test
import java.io.File

/**
 * Scapia Federal credit card rules against the real device shapes: the
 * successful spend extracts amount + merchant + debit, the declined spend
 * and the setting notices are bank alerts with NO transaction extraction,
 * and the statement is a bill with its pay-by date.
 */
class ScapiaRulesTest {
    private val json = Json { ignoreUnknownKeys = true }
    private val engine = RuleEngine()

    private fun rules(): List<RuleDefinition> {
        val candidates =
            listOf(
                File("src/main/assets/default_rules.json"),
                File("app/src/main/assets/default_rules.json"),
            )
        val file = checkNotNull(candidates.firstOrNull { it.exists() })
        return json.decodeFromString(RuleDocument.serializer(), file.readText()).rules
    }

    @Test
    fun `successful spend extracts amount merchant and debit direction`() {
        val result =
            engine.evaluate(
                rules(),
                sender = "TX-FEDSCP-S",
                body =
                    "Hi! Your txn of \u20b95,696.87 at Discover Qatar Doha Qa on your Scapia Federal Visa " +
                        "credit card was successful. Not you? Go to Scapia support on the app.- Federal Bank",
            )
        assertThat(result).isNotNull()
        assertThat(result!!.category).isEqualTo(Category.IMPORTANT)
        assertThat(result.subCategory).isEqualTo(SubCategory.TRANSACTION)
        assertThat(result.extracted["amount"]).isEqualTo("5,696.87")
        assertThat(result.typed.amount("amount")).isEqualTo(5696.87)
        assertThat(result.extracted["merchant"]).isEqualTo("Discover Qatar Doha Qa")
        assertThat(result.extracted["type"]).isEqualTo("debit")
        assertThat(result.extracted["bank"]).isEqualTo("Scapia Federal")
    }

    @Test
    fun `declined spend is a bank alert without transaction extraction`() {
        val result =
            engine.evaluate(
                rules(),
                sender = "VM-FEDSCP-S",
                body =
                    "Txn for \u20b942.00 at Red And White Fleet Retai on your Scapia Federal Visa credit card " +
                        "declined due to an invalid PIN. Retry your payment with the correct PIN. - Federal Bank",
            )
        assertThat(result).isNotNull()
        assertThat(result!!.category).isEqualTo(Category.IMPORTANT)
        assertThat(result.subCategory).isEqualTo(SubCategory.BANK_ALERT)
        assertThat(result.extracted).doesNotContainKey("amount")
        assertThat(result.extracted).doesNotContainKey("type")
    }

    @Test
    fun `statement is a bill with the pay-by date`() {
        val result =
            engine.evaluate(
                rules(),
                sender = "VM-FEDSCP-S",
                body =
                    "Hi! Your Scapia Federal Credit Card statement for JULY-2026 is here. " +
                        "Check your statement on the app and pay by 05-08-2026 - Federal Bank",
            )
        assertThat(result).isNotNull()
        assertThat(result!!.subCategory).isEqualTo(SubCategory.BILL)
        assertThat(result.extracted["due_date"]).isEqualTo("05-08-2026")
        assertThat(result.typed.date("due_date")).isNotNull()
    }

    @Test
    fun `enabled-transactions notice with a decoy last-4 extracts nothing`() {
        val result =
            engine.evaluate(
                rules(),
                sender = "VM-FEDSCP-S",
                body =
                    "Hi! You've enabled domestic ECOM transactions on your Scapia Federal Visa credit card " +
                        "ending with 1234 on 2026-07-01 12:00:00. -Federal Bank",
            )
        assertThat(result).isNotNull()
        assertThat(result!!.subCategory).isEqualTo(SubCategory.BANK_ALERT)
        assertThat(result.extracted).isEmpty()
    }

    @Test
    fun `tap-to-pay advisory with a decoy amount extracts nothing`() {
        val result =
            engine.evaluate(
                rules(),
                sender = "VM-FEDSCP-S",
                body =
                    "Hi, you can't tap to pay for transactions above Rs.5,000. Retry the payment by " +
                        "inserting your Scapia Federal Credit Card into the POS machine. -Federal Bank",
            )
        assertThat(result).isNotNull()
        assertThat(result!!.category).isEqualTo(Category.IMPORTANT)
        assertThat(result.subCategory).isEqualTo(SubCategory.BANK_ALERT)
        assertThat(result.extracted).isEmpty()
    }

    @Test
    fun `near-miss - a non-scapia sender never hits the scapia shapes`() {
        val result =
            engine.evaluate(
                rules(),
                sender = "VM-PROMOZ",
                body = "Your txn of \u20b9500.00 at Some Store on your Scapia Federal Visa credit card was successful.",
            )
        // No FEDSCP sender: none of the scapia rules may claim it.
        if (result != null) {
            assertThat(result.matchedRuleId ?: "").doesNotContain("scapia")
        }
    }

    @Test
    fun `near-miss - an otp mention suppresses the spend rule`() {
        val result =
            engine.evaluate(
                rules(),
                sender = "TX-FEDSCP-S",
                body =
                    "OTP 123456 for txn of \u20b95,696.87 at Some Store on your Scapia Federal Visa " +
                        "credit card was successful.",
            )
        // The success rule carries guards_none: [otp_mention]; whatever else
        // matches, it must not extract a transaction from an OTP body.
        if (result != null && result.subCategory == SubCategory.TRANSACTION) {
            assertThat(result.extracted).doesNotContainKey("amount")
        }
    }
}
