package app.clearsms.domain.categorizer

import app.clearsms.data.rules.RuleDocument
import app.clearsms.data.rules.RuleEngine
import app.clearsms.domain.model.Category
import app.clearsms.domain.model.SubCategory
import app.clearsms.domain.parser.OtpParser
import com.google.common.truth.Truth.assertThat
import kotlinx.serialization.json.Json
import org.junit.Test
import java.io.File

/**
 * Round-AA defect: an IRCTC verification message shaped "NNNNNN is OTP for
 * Mobile number verification of User ..." was filed as a plain service
 * update, not an OTP. Both the parser ([OtpParser]) and the generic OTP
 * rules already understood the leading-code shape (the round-T Axis fix) —
 * the miss was the body-less `irctc-info-01` sender catch-all (priority
 * 200) shadowing the generic OTP rules (priority ≤ 55). The fix follows
 * the established data-layer precedent: the catch-all now declares
 * `guards_none: ["otp_mention"]`, stepping aside whenever the body
 * mentions an OTP so the OTP rules can claim the message.
 *
 * All fixtures are SYNTHETIC — usernames, codes and timestamps are made up.
 */
class IrctcOtpRulesTest {
    private val json = Json { ignoreUnknownKeys = true }
    private val engine = RuleEngine()

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

    private fun categorizer() =
        MessageCategorizer(
            ruleEngine = RuleEngine(),
            senderIdLookup = SenderIdLookup { null },
            contactLookup = ContactLookup { false },
        )

    private fun categorize(
        sender: String,
        body: String,
    ) = categorizer().categorize(
        sender = sender,
        body = body,
        userRules = emptyList(),
        builtinRules = bundledRules(),
    )

    /** Synthetic replica of the defect fixture (username invented). */
    private val irctcOtp =
        "685624 is OTP for Mobile number verification of User traveller42. " +
            "DO NOT disclose it to anyone -IRCTC"

    @Test
    fun `irctc is-OTP-for verification message categorizes as OTP with the code extracted`() {
        val result = categorize("VM-IRCTCI", irctcOtp)
        assertThat(result.category).isEqualTo(Category.OTP)
        assertThat(result.matchedRuleId).startsWith("generic-otp")
        assertThat(result.extracted["otp_code"]).isEqualTo("685624")
    }

    @Test
    fun `otp parser anchors the plain is-OTP-for shape`() {
        assertThat(OtpParser().parseAnchored(irctcOtp)?.code).isEqualTo("685624")
    }

    @Test
    fun `leading digits without an otp anchor stay a service update, never an OTP`() {
        // Bare-digit fallback gating intact: "is OTP" is the anchor, so a
        // plain-English message that merely LEADS with six digits must not
        // be reclassified.
        val result =
            categorize(
                "VM-IRCTCI",
                "123456 is now your ticket number for the journey on 12-08-26. Happy travels -IRCTC",
            )
        assertThat(result.category).isNotEqualTo(Category.OTP)
        assertThat(result.matchedRuleId).isEqualTo("irctc-info-01")
        assertThat(result.extracted).doesNotContainKey("otp_code")
    }

    @Test
    fun `axis SECRET-OTP transaction authorization shape is unregressed`() {
        val result =
            categorize(
                "AD-AXISBK",
                "413423 is SECRET OTP for txn of INR 1205.23 on Axis Bank card XX0266 at AIRTEL PAY on " +
                    "01-08-26 18:57:01. OTP valid for 5 mins. Please do not share this OTP.",
            )
        assertThat(result.category).isEqualTo(Category.OTP)
        assertThat(result.extracted["otp_code"]).isEqualTo("413423")
        assertThat(result.subCategory).isNotEqualTo(SubCategory.TRANSACTION)
    }

    @Test
    fun `keyword-anchored otp still beats a transaction derivation from an irctc refund`() {
        // Precedence unchanged: an IRCTC body that matches the refund rule
        // (sub_category transaction) but carries an anchored OTP must
        // surface as OTP, with the money extracts dropped.
        val result =
            categorize(
                "VM-IRCTCI",
                "Refund of Rs.740 initiated. 582913 is OTP for confirming your refund request -IRCTC",
            )
        assertThat(result.category).isEqualTo(Category.OTP)
        assertThat(result.extracted["otp_code"]).isEqualTo("582913")
        assertThat(result.extracted).doesNotContainKey("type")
    }

    @Test
    fun `guarded catch-all is mirrored identically in the rules directory master`() {
        val master =
            File(
                listOf("../rules", "rules").first { File(it).isDirectory },
                "india/travel/irctc.json",
            )
        val masterRule =
            json
                .decodeFromString(RuleDocument.serializer(), master.readText())
                .rules
                .first { it.id == "irctc-info-01" }
        val bundledRule = bundledRules().first { it.id == "irctc-info-01" }
        assertThat(bundledRule.match).isEqualTo(masterRule.match)
        assertThat(masterRule.match.guardsNone).contains("otp_mention")
    }
}
