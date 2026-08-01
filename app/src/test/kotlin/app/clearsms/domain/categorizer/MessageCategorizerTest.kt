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

class MessageCategorizerTest {
    private val noSenderId = SenderIdLookup { null }
    private val noContact = ContactLookup { false }

    private fun categorizer(
        senderIdLookup: SenderIdLookup = noSenderId,
        contactLookup: ContactLookup = noContact,
    ) = MessageCategorizer(
        ruleEngine = RuleEngine(),
        senderIdLookup = senderIdLookup,
        contactLookup = contactLookup,
    )

    private fun rule(
        id: String,
        category: String,
        bodyPattern: String = "(?i)hello",
        priority: Int = 10,
    ) = RuleDefinition(
        id = id,
        name = id,
        priority = priority,
        match = RuleMatch(bodyPattern = bodyPattern),
        action = RuleAction(category = category),
    )

    @Test
    fun `user rule beats builtin rule`() {
        val result =
            categorizer().categorize(
                sender = "ANYONE",
                body = "hello world",
                userRules = listOf(rule("user-1", "personal")),
                builtinRules = listOf(rule("builtin-1", "promotional", priority = 999)),
            )
        assertThat(result.matchedRuleId).isEqualTo("user-1")
        assertThat(result.category).isEqualTo(Category.PERSONAL)
    }

    @Test
    fun `builtin rule beats sender directory`() {
        val senderId = SenderIdLookup { SenderInfo("Some Brand", Category.PROMOTIONAL, null) }
        val result =
            categorizer(senderIdLookup = senderId).categorize(
                sender = "BRAND",
                body = "hello offer",
                userRules = emptyList(),
                builtinRules = listOf(rule("builtin-1", "important")),
            )
        assertThat(result.matchedRuleId).isEqualTo("builtin-1")
        assertThat(result.category).isEqualTo(Category.IMPORTANT)
    }

    @Test
    fun `sender directory used when no rule matches`() {
        val senderId =
            SenderIdLookup { id ->
                if (id == "AMZNIN") SenderInfo("Amazon India", Category.PROMOTIONAL, "ecommerce") else null
            }
        val result =
            categorizer(senderIdLookup = senderId).categorize(
                sender = "AMZNIN",
                body = "Big billion sale is live!",
                userRules = emptyList(),
                builtinRules = emptyList(),
            )
        assertThat(result.category).isEqualTo(Category.PROMOTIONAL)
        assertThat(result.matchedRuleId).isNull()
    }

    @Test
    fun `content fallback detects otp`() {
        val result =
            categorizer().categorize(
                sender = "UNKNWN",
                body = "Your OTP is 482910. Do not share it.",
                userRules = emptyList(),
                builtinRules = emptyList(),
            )
        assertThat(result.category).isEqualTo(Category.OTP)
        assertThat(result.subCategory).isEqualTo(SubCategory.OTP)
        assertThat(result.extracted["otp_code"]).isEqualTo("482910")
    }

    @Test
    fun `content fallback detects transaction`() {
        val result =
            categorizer().categorize(
                sender = "SOMEBK",
                body = "Rs.2,500.00 debited from A/c XX1234 on 12-07-26. Avl Bal Rs.10,000.00",
                userRules = emptyList(),
                builtinRules = emptyList(),
            )
        assertThat(result.category).isEqualTo(Category.IMPORTANT)
        assertThat(result.subCategory).isEqualTo(SubCategory.TRANSACTION)
    }

    @Test
    fun `content fallback detects scam`() {
        val result =
            categorizer().categorize(
                sender = "SPAM",
                body = "You have won a lucky draw prize! Claim now at bit.ly/win123",
                userRules = emptyList(),
                builtinRules = emptyList(),
            )
        assertThat(result.category).isEqualTo(Category.PROMOTIONAL)
        assertThat(result.subCategory).isEqualTo(SubCategory.SCAM)
    }

    @Test
    fun `contact sender is personal`() {
        val contacts = ContactLookup { it == "+919876543210" }
        val result =
            categorizer(contactLookup = contacts).categorize(
                sender = "+919876543210",
                body = "See you at 6!",
                userRules = emptyList(),
                builtinRules = emptyList(),
            )
        assertThat(result.category).isEqualTo(Category.PERSONAL)
    }

    @Test
    fun `unknown sender with plain content is unknown`() {
        val result =
            categorizer().categorize(
                sender = "+911234509876",
                body = "See you at 6!",
                userRules = emptyList(),
                builtinRules = emptyList(),
            )
        assertThat(result.category).isEqualTo(Category.UNKNOWN)
    }

    @Test
    fun `directory sender with anchored otp content is lifted to the otp category`() {
        val senderId = SenderIdLookup { SenderInfo("Some Bank", Category.IMPORTANT, "banking") }
        val result =
            categorizer(senderIdLookup = senderId).categorize(
                sender = "SOMEBK",
                body = "Your OTP is 482910. Do not share it.",
                userRules = emptyList(),
                builtinRules = emptyList(),
            )
        // A keyword-anchored code is a real OTP: the directory's IMPORTANT
        // must not keep it out of the OTP category (the OTP notification
        // and auto-delete only act on Category.OTP with an extracted code).
        assertThat(result.category).isEqualTo(Category.OTP)
        assertThat(result.subCategory).isEqualTo(SubCategory.OTP)
        assertThat(result.extracted["otp_code"]).isEqualTo("482910")
    }
}
