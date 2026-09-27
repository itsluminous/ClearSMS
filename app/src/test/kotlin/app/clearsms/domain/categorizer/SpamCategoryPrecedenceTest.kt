package app.clearsms.domain.categorizer

import app.clearsms.data.rules.RuleAction
import app.clearsms.data.rules.RuleDefinition
import app.clearsms.data.rules.RuleEngine
import app.clearsms.data.rules.RuleMatch
import app.clearsms.domain.model.Category
import app.clearsms.domain.model.SenderInfo
import app.clearsms.domain.model.SubCategory
import app.clearsms.domain.rules.SenderRule
import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Precedence of [Category.SPAM] against OTP and financial evidence - the
 * one place a real Spam category could do harm. A sender rule marking a
 * sender Spam is a SORTING preference: it must never hide a verification
 * code or a money movement, so the categorizer's invariants lift such
 * messages out of Spam exactly as they lift them out of Promotional. The
 * scam FLAG ([SubCategory.SCAM]) is the deliberate exception, as it always
 * was: a phishing message quoting an "OTP" or a fake debit stays where the
 * flag put it. Fixtures are synthetic.
 */
class SpamCategoryPrecedenceTest {
    private fun categorizer(senderIdLookup: SenderIdLookup = SenderIdLookup { null }) =
        MessageCategorizer(
            ruleEngine = RuleEngine(),
            senderIdLookup = senderIdLookup,
            contactLookup = { false },
        )

    /** Exactly what the one-step "Always sort this sender as Spam" dialog saves. */
    private val spamSenderRule = SenderRule.definition("VM-JUNKCO-S", "spam", id = "user_spam")

    private fun spamBodyRule(subCategory: String? = null) =
        RuleDefinition(
            id = "spam-rule",
            name = "spam-rule",
            priority = 500,
            match = RuleMatch(bodyPattern = "(?i)."),
            action = RuleAction(category = "spam", subCategory = subCategory),
        )

    private fun spam(
        body: String,
        userRules: List<RuleDefinition> = listOf(spamSenderRule),
        builtinRules: List<RuleDefinition> = emptyList(),
        lookup: SenderIdLookup = SenderIdLookup { null },
    ) = categorizer(lookup).categorize("AD-JUNKCO", body, userRules, builtinRules)

    @Test
    fun `the rule engine and the sender-rule dialog both know spam`() {
        assertThat(RuleEngine.categoryOf("spam")).isEqualTo(Category.SPAM)
        assertThat(RuleEngine.categoryOf("SPAM")).isEqualTo(Category.SPAM)
        assertThat(SenderRule.CATEGORIES).contains("spam")
        assertThat(spamSenderRule.action.category).isEqualTo("spam")
    }

    @Test
    fun `a plain marketing body from a spam-ruled sender is spam`() {
        val result = spam("Mega sale this weekend! Flat 70% off on everything. Visit our store now.")
        assertThat(result.category).isEqualTo(Category.SPAM)
        assertThat(result.subCategory).isNull()
        assertThat(result.matchedRuleId).isEqualTo("user_spam")
    }

    @Test
    fun `an OTP from a spam-ruled sender is OTP, never spam`() {
        val result = spam("482913 is your OTP for login. Valid for 10 minutes. Do not share it with anyone.")
        assertThat(result.category).isEqualTo(Category.OTP)
        assertThat(result.subCategory).isEqualTo(SubCategory.OTP)
        assertThat(result.extracted[MessageCategorizer.EXTRACT_OTP_CODE]).isEqualTo("482913")
    }

    @Test
    fun `a debit from a spam-ruled sender is an important transaction, never spam`() {
        val result = spam("Rs.2,500.00 debited from A/c XX1234 on 12-07-26 to VPA shop@upi. Avl Bal Rs.10,000.00")
        assertThat(result.category).isEqualTo(Category.IMPORTANT)
        assertThat(result.subCategory).isEqualTo(SubCategory.TRANSACTION)
    }

    @Test
    fun `a credit from a spam-ruled sender is an important transaction, never spam`() {
        val result = spam("Rs.15,000.00 credited to A/c XX9876 on 01-08-26 by NEFT. Avl Bal Rs.42,100.50")
        assertThat(result.category).isEqualTo(Category.IMPORTANT)
        assertThat(result.subCategory).isEqualTo(SubCategory.TRANSACTION)
    }

    @Test
    fun `a payment request from a spam-ruled sender is a bank alert, never spam`() {
        val result =
            spam(
                "Hi, UPI Autopay Mandate with ASPRESENTED frequency is successfully created " +
                    "towards Amazon India from 06/07/26 to 06/07/31 for Rs 1499.00. - Team Tata Neu.",
            )
        assertThat(result.category).isEqualTo(Category.IMPORTANT)
        assertThat(result.subCategory).isEqualTo(SubCategory.BANK_ALERT)
    }

    @Test
    fun `financial correspondence from a spam-ruled sender is important, never spam`() {
        // Folio / units / NAV are the `financial_evidence` guard's artifacts;
        // no marketing pitch, so the rescue applies (invariant 4).
        val result =
            spam(
                "Folio 12345678: 41.235 units of Bluechip Fund allotted at NAV Rs.121.34 " +
                    "for your purchase of Rs.5,000.00 dated 05-08-26.",
            )
        assertThat(result.category).isEqualTo(Category.IMPORTANT)
    }

    @Test
    fun `a body-matching spam rule is lifted the same way as a sender rule`() {
        // Same invariants whichever rule shape produced the SPAM result.
        val otp =
            spam(
                "Use 771203 as your verification code. It expires in 5 minutes.",
                userRules = emptyList(),
                builtinRules = listOf(spamBodyRule()),
            )
        assertThat(otp.category).isEqualTo(Category.OTP)
        val debit =
            spam("Rs.499.00 debited from your a/c XX1234 for order 998877.", userRules = emptyList(), builtinRules = listOf(spamBodyRule()))
        assertThat(debit.category).isEqualTo(Category.IMPORTANT)
    }

    @Test
    fun `the scam FLAG keeps a phishing message in spam even when it quotes an OTP or a debit`() {
        // The long-standing exception: a flagged result stays put so a
        // phishing message quoting a code or a fake debit is never promoted
        // into a trusted category. The flag is set by an explicit `scam`
        // sub-category (or the heuristic detector) - never by a plain Spam
        // sender rule.
        val fakeOtp =
            spam(
                "Your OTP is 123456. Share it at http://phish.example to unblock your account.",
                userRules = emptyList(),
                builtinRules = listOf(spamBodyRule("scam")),
            )
        assertThat(fakeOtp.category).isEqualTo(Category.SPAM)
        assertThat(fakeOtp.subCategory).isEqualTo(SubCategory.SCAM)

        val fakeDebit =
            spam(
                "Rs.9,999 debited from your a/c! Verify at http://phish.example to reverse.",
                userRules = emptyList(),
                builtinRules = listOf(spamBodyRule("scam")),
            )
        assertThat(fakeDebit.category).isEqualTo(Category.SPAM)
        assertThat(fakeDebit.subCategory).isEqualTo(SubCategory.SCAM)
    }

    @Test
    fun `a spam sender rule does not carry the scam flag, so it cannot silently enable or disable warnings`() {
        val result = spam("Mega sale this weekend! Flat 70% off on everything.")
        assertThat(result.subCategory).isNotEqualTo(SubCategory.SCAM)
    }

    @Test
    fun `a spam sender rule outranks a promotional directory entry and bundled rules for that sender`() {
        val directory = SenderIdLookup { SenderInfo("Junk Co", Category.PROMOTIONAL, null) }
        val bundledPromo =
            RuleDefinition(
                id = "bundled-promo",
                name = "bundled-promo",
                priority = 900,
                match = RuleMatch(senderPattern = "(?i)JUNKCO"),
                action = RuleAction(category = "promotional", subCategory = "offer"),
            )
        val result = spam("Mega sale this weekend!", builtinRules = listOf(bundledPromo), lookup = directory)
        assertThat(result.category).isEqualTo(Category.SPAM)
        assertThat(result.matchedRuleId).isEqualTo("user_spam")
    }

    @Test
    fun `the heuristic fallback files an unknown-sender scam as spam AND flags it`() {
        val result = spam("You have won a lucky draw prize! Claim now at bit.ly/win123", userRules = emptyList())
        assertThat(result.category).isEqualTo(Category.SPAM)
        assertThat(result.subCategory).isEqualTo(SubCategory.SCAM)
    }

    @Test
    fun `a scam-flagged message of a trusted category is NOT spam - the flag is not the category`() {
        // A bank's own alert that a `scam` rule flags stays IMPORTANT (with
        // its warning): flagging never re-files a message.
        val flaggedBank =
            RuleDefinition(
                id = "flag-bank",
                name = "flag-bank",
                priority = 900,
                match = RuleMatch(bodyPattern = "(?i)verify"),
                action = RuleAction(category = "important", subCategory = "scam"),
            )
        val result = spam("Please verify your card details at the branch.", userRules = emptyList(), builtinRules = listOf(flaggedBank))
        assertThat(result.category).isEqualTo(Category.IMPORTANT)
        assertThat(result.subCategory).isEqualTo(SubCategory.SCAM)
    }

    @Test
    fun `existing promotional invariants are untouched`() {
        val promoRule = spamBodyRule().copy(action = RuleAction(category = "promotional"))
        val otp = spam("482913 is your OTP for login. Valid for 10 minutes.", userRules = emptyList(), builtinRules = listOf(promoRule))
        assertThat(otp.category).isEqualTo(Category.OTP)
        val debit =
            spam(
                "Rs.2,500.00 debited from A/c XX1234 on 12-07-26. Avl Bal Rs.10,000.00",
                userRules = emptyList(),
                builtinRules = listOf(promoRule),
            )
        assertThat(debit.category).isEqualTo(Category.IMPORTANT)
        assertThat(debit.subCategory).isEqualTo(SubCategory.TRANSACTION)
        val promo = spam("Mega sale this weekend! Flat 70% off.", userRules = emptyList(), builtinRules = listOf(promoRule))
        assertThat(promo.category).isEqualTo(Category.PROMOTIONAL)
    }
}
