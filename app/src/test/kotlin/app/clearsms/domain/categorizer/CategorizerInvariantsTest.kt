package app.clearsms.domain.categorizer

import app.clearsms.data.rules.RuleAction
import app.clearsms.data.rules.RuleDefinition
import app.clearsms.data.rules.RuleEngine
import app.clearsms.data.rules.RuleMatch
import app.clearsms.domain.model.Category
import app.clearsms.domain.model.SenderInfo
import app.clearsms.domain.model.SubCategory
import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Post-condition invariants of [MessageCategorizer]:
 * an extracted transaction is never PROMOTIONAL, and an extractable OTP code
 * always beats a promotional classification — with narrow, deliberate
 * exceptions for scam results and UPI-mandate lifecycle notices.
 */
class CategorizerInvariantsTest {
    private val promoDirectory = SenderIdLookup { SenderInfo("Some Brand", Category.PROMOTIONAL, null) }

    private fun categorizer(senderIdLookup: SenderIdLookup = SenderIdLookup { null }) =
        MessageCategorizer(
            ruleEngine = RuleEngine(),
            senderIdLookup = senderIdLookup,
            contactLookup = { false },
        )

    private fun promoRule(subCategory: String? = null) =
        RuleDefinition(
            id = "promo-rule",
            name = "promo-rule",
            priority = 500,
            match = RuleMatch(bodyPattern = "(?i)."),
            action = RuleAction(category = "promotional", subCategory = subCategory),
        )

    @Test
    fun `rule-tagged promotional transaction is promoted to important`() {
        val result =
            categorizer().categorize(
                sender = "MFHOUS",
                body = "Your purchase of Rs.5,000.00 has been processed.",
                userRules = emptyList(),
                builtinRules = listOf(promoRule(subCategory = "transaction")),
            )
        assertThat(result.category).isEqualTo(Category.IMPORTANT)
        assertThat(result.subCategory).isEqualTo(SubCategory.TRANSACTION)
    }

    @Test
    fun `directory-promotional sender with parseable debit becomes important`() {
        val result =
            categorizer(promoDirectory).categorize(
                sender = "SOMEBR",
                body = "Rs.499.00 debited from your a/c XX1234 for order 998877.",
                userRules = emptyList(),
                builtinRules = emptyList(),
            )
        assertThat(result.category).isEqualTo(Category.IMPORTANT)
        assertThat(result.subCategory).isEqualTo(SubCategory.TRANSACTION)
    }

    @Test
    fun `upi mandate creation is informational despite carrying an amount`() {
        val result =
            categorizer(promoDirectory).categorize(
                sender = "TATANU",
                body =
                    "Hi, UPI Autopay Mandate with ASPRESENTED frequency is successfully created " +
                        "towards Amazon India from 06/07/26 to 06/07/31 for Rs 1499.00. - Team Tata Neu.",
                userRules = emptyList(),
                builtinRules = emptyList(),
            )
        assertThat(result.category).isEqualTo(Category.INFORMATIONAL)
        assertThat(result.subCategory).isEqualTo(SubCategory.BANK_ALERT)
    }

    @Test
    fun `upi mandate cancellation is informational and never a transaction`() {
        val result =
            categorizer(promoDirectory).categorize(
                sender = "TATANU",
                body =
                    "Hi, you have successfully cancelled the scheduled ASPRESENTED payment of " +
                        "amount MAX Rs. 1499.00 towards Amazon India. - Team Tata Neu",
                userRules = emptyList(),
                builtinRules = emptyList(),
            )
        assertThat(result.category).isEqualTo(Category.INFORMATIONAL)
        assertThat(result.subCategory).isNotEqualTo(SubCategory.TRANSACTION)
    }

    @Test
    fun `scam classification is never promoted even with a fake debit`() {
        val result =
            categorizer().categorize(
                sender = "FRAUD",
                body = "Rs.9,999 debited from your a/c! Verify at http://phish.example to reverse.",
                userRules = emptyList(),
                builtinRules = listOf(promoRule(subCategory = "scam")),
            )
        assertThat(result.category).isEqualTo(Category.PROMOTIONAL)
        assertThat(result.subCategory).isEqualTo(SubCategory.SCAM)
    }

    @Test
    fun `extractable otp code wins over directory-promotional sender`() {
        val result =
            categorizer(promoDirectory).categorize(
                sender = "ACTBRD",
                body =
                    "Your OTP is: 573372. Thank You for your interest in Act Fibernet. " +
                        "The OTP will be valid for 5 minutes.",
                userRules = emptyList(),
                builtinRules = emptyList(),
            )
        assertThat(result.category).isEqualTo(Category.OTP)
        assertThat(result.extracted[MessageCategorizer.EXTRACT_OTP_CODE]).isEqualTo("573372")
    }

    @Test
    fun `extractable otp code wins over a promotional rule match`() {
        val result =
            categorizer().categorize(
                sender = "ACTBRD",
                body = "Your OTP is: 573372. Thank You for your interest in Act Fibernet.",
                userRules = emptyList(),
                builtinRules = listOf(promoRule(subCategory = "offer")),
            )
        assertThat(result.category).isEqualTo(Category.OTP)
        assertThat(result.extracted[MessageCategorizer.EXTRACT_OTP_CODE]).isEqualTo("573372")
    }

    @Test
    fun `promo that merely mentions a number stays promotional`() {
        val result =
            categorizer().categorize(
                sender = "SALESX",
                body = "Mega sale! Flat 50% off on orders above 599000 this weekend only.",
                userRules = emptyList(),
                builtinRules = listOf(promoRule(subCategory = "offer")),
            )
        assertThat(result.category).isEqualTo(Category.PROMOTIONAL)
    }

    @Test
    fun `plain promotional message is untouched by the invariants`() {
        val result =
            categorizer(promoDirectory).categorize(
                sender = "SOMEBR",
                body = "Weekend blowout! Everything must go. Visit our store today.",
                userRules = emptyList(),
                builtinRules = emptyList(),
            )
        assertThat(result.category).isEqualTo(Category.PROMOTIONAL)
    }

    @Test
    fun `informational results are untouched by the invariants`() {
        val informationalRule =
            RuleDefinition(
                id = "info-rule",
                name = "info-rule",
                priority = 500,
                match = RuleMatch(bodyPattern = "(?i)traded\\s+value"),
                action = RuleAction(category = "informational", subCategory = "investment"),
            )
        val result =
            categorizer().categorize(
                sender = "NSESMS",
                body = "Your traded value for 29-JUN-26 CM Rs 12050.88 combined across exchanges.",
                userRules = emptyList(),
                builtinRules = listOf(informationalRule),
            )
        assertThat(result.category).isEqualTo(Category.INFORMATIONAL)
        assertThat(result.subCategory).isEqualTo(SubCategory.INVESTMENT)
    }
}
