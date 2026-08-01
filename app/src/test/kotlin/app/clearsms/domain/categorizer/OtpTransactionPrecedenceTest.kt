package app.clearsms.domain.categorizer

import app.clearsms.data.rules.RuleDocument
import app.clearsms.data.rules.RuleEngine
import app.clearsms.domain.model.Category
import app.clearsms.domain.model.SubCategory
import com.google.common.truth.Truth.assertThat
import kotlinx.serialization.json.Json
import org.junit.Test
import java.io.File

/**
 * Round-T invariant: a keyword-ANCHORED OTP beats a transaction
 * categorization (an authorization request quotes an amount, a card and a
 * merchant, but nothing has moved), mirroring the existing OTP-beats-
 * promotional invariant. The bare six-digits-near-a-context-word fallback
 * never reclassifies — a spend alert whose advisory merely says "PIN/OTP"
 * must stay a transaction.
 */
class OtpTransactionPrecedenceTest {
    private val json = Json { ignoreUnknownKeys = true }

    private fun categorizer(senderIdLookup: SenderIdLookup = SenderIdLookup { null }) =
        MessageCategorizer(
            ruleEngine = RuleEngine(),
            senderIdLookup = senderIdLookup,
            contactLookup = ContactLookup { false },
        )

    private fun bundledRules() =
        json
            .decodeFromString(
                RuleDocument.serializer(),
                listOf(
                    File("src/main/assets/default_rules.json"),
                    File("app/src/main/assets/default_rules.json"),
                ).first { it.exists() }
                    .readText(),
            ).rules

    private val axisTxnOtp =
        "413423 is SECRET OTP for txn of INR 1205.23 on Axis Bank card XX0266 at AIRTEL PAY on " +
            "01-08-26 18:57:01. OTP valid for 5 mins. Please do not share this OTP."

    @Test
    fun `transaction otp categorizes as OTP with the code extracted against the bundled rules`() {
        val result =
            categorizer().categorize(
                sender = "AD-AXISBK",
                body = axisTxnOtp,
                userRules = emptyList(),
                builtinRules = bundledRules(),
            )
        assertThat(result.category).isEqualTo(Category.OTP)
        assertThat(result.extracted["otp_code"]).isEqualTo("413423")
        assertThat(result.subCategory).isNotEqualTo(SubCategory.TRANSACTION)
    }

    @Test
    fun `anchored otp beats a rule-matched transaction and drops its money extracts`() {
        // A hypothetical bank rule without the otp_mention guard matches the
        // authorization text as a "transaction" — the invariant must undo it.
        val rule =
            json.decodeFromString(
                app.clearsms.data.rules.RuleDefinition
                    .serializer(),
                """
                {"id":"t-loose-txn","priority":900,
                 "match":{"body_pattern":"(?i)txn\\s+of\\s+INR\\s*([\\d,.]+)\\s+on\\s+Axis"},
                 "action":{"category":"important","sub_category":"transaction",
                   "extract":{"amount":"$1","type":"debit"}}}
                """.trimIndent(),
            )
        val result =
            categorizer().categorize(
                sender = "AD-AXISBK",
                body = axisTxnOtp,
                userRules = listOf(rule),
                builtinRules = emptyList(),
            )
        assertThat(result.category).isEqualTo(Category.OTP)
        assertThat(result.subCategory).isEqualTo(SubCategory.OTP)
        assertThat(result.extracted["otp_code"]).isEqualTo("413423")
        // No amount/type survive, so nothing downstream can derive a debit.
        assertThat(result.extracted).doesNotContainKey("amount")
        assertThat(result.extracted).doesNotContainKey("type")
    }

    @Test
    fun `a plain spend alert still categorizes as a transaction against the bundled rules`() {
        val result =
            categorizer().categorize(
                sender = "VM-HDFCBK-S",
                body = "Sent Rs.500.00 From HDFC Bank A/C x1234 To SWIGGY On 12/07/26 Ref 519912345678 Not You? Call 18002586161",
                userRules = emptyList(),
                builtinRules = bundledRules(),
            )
        assertThat(result.category).isEqualTo(Category.IMPORTANT)
        assertThat(result.subCategory).isEqualTo(SubCategory.TRANSACTION)
    }

    @Test
    fun `a spend alert with a pin-otp advisory stays a transaction`() {
        val result =
            categorizer().categorize(
                sender = "VM-HDFCBK",
                body =
                    "ALERT:Rs.740 spent via CREDIT Card xx0266 at AMAZON on 2026-08-01:18:57:01 without " +
                        "PIN/OTP.Not you?Call 08061914588.",
                userRules = emptyList(),
                builtinRules = emptyList(),
            )
        assertThat(result.category).isEqualTo(Category.IMPORTANT)
        assertThat(result.subCategory).isEqualTo(SubCategory.TRANSACTION)
    }
}
