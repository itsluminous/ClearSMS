package app.clearsms.domain.categorizer

import app.clearsms.data.rules.RuleAction
import app.clearsms.data.rules.RuleDefinition
import app.clearsms.data.rules.RuleEngine
import app.clearsms.data.rules.RuleMatch
import app.clearsms.domain.model.Category
import app.clearsms.domain.model.SenderInfo
import app.clearsms.domain.model.SubCategory
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import org.junit.Test

/**
 * Invariant 4 of [MessageCategorizer]: financial content is never
 * PROMOTIONAL, even when no transaction derives. Evidence is the data-driven
 * `financial_evidence` guard - transactional ARTIFACTS (folio/UTR/SR ids,
 * units allotted, NAV, instalment/redemption/settlement/refund lifecycle
 * verbs, TDS summaries, recorded payments, order-number-plus-amount), never
 * amounts alone.
 *
 * THE HARD EDGE, proven by the near-miss suite: promos legitimately quote
 * money ("cashback up to Rs.500", "loan @10.5%", "3 pts per Rs.100"). None
 * of these may be rescued - and a marketing pitch that name-drops an
 * artifact is vetoed outright by the `marketing_pitch` guard.
 */
class FinancialEvidenceInvariantTest {
    private val promoDirectory = SenderIdLookup { SenderInfo("Some Brand", Category.PROMOTIONAL, null) }

    private fun categorizer(senderIdLookup: SenderIdLookup = promoDirectory) =
        MessageCategorizer(
            ruleEngine = RuleEngine(),
            senderIdLookup = senderIdLookup,
            contactLookup = { false },
        )

    /** A hostile promo rule claiming everything, like an over-broad brand rule would. */
    private val promoRule =
        RuleDefinition(
            id = "promo-rule",
            name = "promo-rule",
            priority = 500,
            match = RuleMatch(bodyPattern = "(?i)."),
            action = RuleAction(category = "promotional"),
        )

    private fun categorizeUnderPromoRule(body: String) =
        categorizer().categorize(
            sender = "SOMEBR",
            body = body,
            userRules = emptyList(),
            builtinRules = listOf(promoRule),
        )

    // region rescued shapes - transactional artifacts, no derivable transaction

    private val rescued =
        mapOf(
            "sip instalment processed with units allotted" to
                "Your SIP instalment of Rs.2,500.00 in Folio 5023/47 has been processed. " +
                "Units allotted: 9.827 at NAV Rs.254.41.",
            "card refund initiated" to
                "Refund of Rs.1,240.50 towards credit balance on your card has been initiated. " +
                "It will be credited within 7 working days. SR 482913605.",
            "tds quarterly summary" to
                "Total TDS by all deductors of PAN ABXPK1234X for Qtr ending 30-Jun-26 is " +
                "Rs 48,500. View 26AS for details.",
            "mf redemption processed" to
                "Redemption of Rs.25,000.00 from your fund, Folio 5098765432, has been processed. " +
                "Amount will be credited to your registered bank account within 3 working days.",
            "payment recorded by third-party ledger" to
                "Payment of Rs.2,500.00 recorded against Invoice INV-2214 to Sharma Traders.",
            "order confirmation with number and amount" to
                "Your order no. 10784536 is confirmed! Total Rs.599.00. Arriving in 30 mins.",
        )

    @Test
    fun `financial artifacts demote a promotional rule result to important`() {
        for ((label, body) in rescued) {
            val result = categorizeUnderPromoRule(body)
            assertWithMessage("'$label' must never stay promotional")
                .that(result.category)
                .isEqualTo(Category.IMPORTANT)
        }
    }

    @Test
    fun `financial artifacts demote a promotional directory result to important`() {
        for ((label, body) in rescued) {
            val result =
                categorizer().categorize(
                    sender = "SOMEBR",
                    body = body,
                    userRules = emptyList(),
                    builtinRules = emptyList(),
                )
            assertWithMessage("'$label' must never stay promotional")
                .that(result.category)
                .isEqualTo(Category.IMPORTANT)
        }
    }

    @Test
    fun `rescue lands as general financial correspondence when no obligation derives`() {
        val result = categorizeUnderPromoRule(rescued.getValue("tds quarterly summary"))
        assertThat(result.subCategory).isEqualTo(SubCategory.GENERAL)
    }

    @Test
    fun `rescue refines to bill when the body carries a parseable obligation`() {
        val result =
            categorizeUnderPromoRule(
                "Your electricity bill of Rs.1,140.00 has been generated for Aug-26. SR no. 482119. " +
                    "Pay before the due date to avoid late fee.",
            )
        assertThat(result.category).isEqualTo(Category.IMPORTANT)
        assertThat(result.subCategory).isEqualTo(SubCategory.BILL)
    }

    // endregion

    // region THE HARD EDGE - promo near-misses that must never be rescued

    private val nearMisses =
        mapOf(
            "cashback pitch" to
                "Flat 20% cashback up to Rs.500 on your first bill payment! Use code NEWUSER. T&C apply.",
            "loan offer quoting money and a bank-less lender" to
                "Pre-approved personal loan of Rs.5,00,000 @10.5% p.a. for you from Sunrise Finance. " +
                "Zero paperwork. Apply now!",
            "pre-approved refund-of-stress style pitch" to
                "Tired of EMIs? Get an instant refund of stress with our pre-approved credit line. Apply today!",
            "per-unit rewards pitch" to
                "Earn 3 reward points on every Rs.100 spent on your ShopMore card. Upgrade now!",
        )

    @Test
    fun `promo near-misses quoting money stay promotional`() {
        for ((label, body) in nearMisses) {
            val result = categorizeUnderPromoRule(body)
            assertWithMessage("'$label' must not be rescued - the amount is marketing, not movement")
                .that(result.category)
                .isEqualTo(Category.PROMOTIONAL)
        }
    }

    @Test
    fun `a marketing pitch name-dropping an artifact is vetoed by the pitch guard`() {
        // "units allotted" is evidence - but "why invest" / "start investing"
        // marks the body as a pitch, and the pitch veto wins.
        val result =
            categorizeUnderPromoRule(
                "Why invest anywhere else? Investors saw 42.5 units allotted per Rs.5,000 SIP " +
                    "last month. Start investing today!",
            )
        assertThat(result.category).isEqualTo(Category.PROMOTIONAL)
    }

    @Test
    fun `scam results are never rescued by financial evidence`() {
        // A phishing message quoting a fake refund must stay in the scam
        // bucket - the existing scam exception precedes every rescue.
        val scamRule =
            RuleDefinition(
                id = "scam-rule",
                name = "scam-rule",
                priority = 600,
                match = RuleMatch(bodyPattern = "(?i)refund"),
                action = RuleAction(category = "promotional", subCategory = "scam"),
            )
        val result =
            categorizer().categorize(
                sender = "SOMEBR",
                body = "Your income tax refund of Rs.15,000 is initiated! Verify PAN at bit.ly/xyz to claim.",
                userRules = emptyList(),
                builtinRules = listOf(scamRule),
            )
        assertThat(result.category).isEqualTo(Category.PROMOTIONAL)
        assertThat(result.subCategory).isEqualTo(SubCategory.SCAM)
    }

    // endregion
}
