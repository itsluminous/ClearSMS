package app.clearsms.domain.categorizer

import app.clearsms.data.rules.RuleDefinition
import app.clearsms.data.rules.RuleEngine
import app.clearsms.domain.model.CategorizationResult
import app.clearsms.domain.model.Category
import app.clearsms.domain.model.SubCategory
import app.clearsms.domain.parser.OtpParser
import app.clearsms.domain.parser.ReminderParser
import app.clearsms.domain.parser.ScamDetector
import app.clearsms.domain.parser.TransactionParser

/**
 * Assigns a category to an incoming message using the priority chain:
 *
 * 1. user-defined rules,
 * 2. bundled (builtin) rules,
 * 3. sender ID directory,
 * 4. content-based regex fallback (OTP / transaction / reminder / scam),
 * 5. contact lookup (contacts → PERSONAL),
 * 6. UNKNOWN.
 */
class MessageCategorizer(
    private val ruleEngine: RuleEngine,
    private val senderIdLookup: SenderIdLookup,
    private val contactLookup: ContactLookup,
    private val otpParser: OtpParser = OtpParser(),
    private val transactionParser: TransactionParser = TransactionParser(),
    private val reminderParser: ReminderParser = ReminderParser(),
    private val scamDetector: ScamDetector = ScamDetector(),
) {
    fun categorize(
        sender: String,
        body: String,
        userRules: List<RuleDefinition>,
        builtinRules: List<RuleDefinition>,
    ): CategorizationResult {
        // Regex engines only ever see a bounded prefix of the body: a
        // concatenated multipart SMS can be tens of thousands of characters,
        // which turns even mildly backtracking patterns into a denial of
        // service. The full body is still stored and displayed unchanged.
        val evalBody = body.take(MAX_EVAL_BODY_LENGTH)
        ruleEngine.evaluate(userRules, sender, evalBody)?.let { return it }
        ruleEngine.evaluate(builtinRules, sender, evalBody)?.let { return it }

        senderIdLookup.lookup(sender)?.let { info ->
            return CategorizationResult(
                category = info.category,
                subCategory = contentSubCategory(sender, evalBody),
            )
        }

        contentFallback(sender, evalBody)?.let { return it }

        if (contactLookup.isContact(sender)) {
            return CategorizationResult(category = Category.PERSONAL)
        }

        return CategorizationResult(category = Category.UNKNOWN)
    }

    /** Content-based regex fallback for senders no rule or directory entry knows. */
    private fun contentFallback(
        sender: String,
        body: String,
    ): CategorizationResult? {
        otpParser.parse(body)?.let { otp ->
            return CategorizationResult(
                category = Category.OTP,
                subCategory = SubCategory.OTP,
                extracted = mapOf(EXTRACT_OTP_CODE to otp.code),
            )
        }
        if (transactionParser.parse(sender, body) != null) {
            return CategorizationResult(category = Category.IMPORTANT, subCategory = SubCategory.TRANSACTION)
        }
        if (reminderParser.parse(sender, body) != null) {
            return CategorizationResult(category = Category.IMPORTANT, subCategory = SubCategory.BILL)
        }
        if (scamDetector.isScam(body)) {
            return CategorizationResult(category = Category.PROMOTIONAL, subCategory = SubCategory.SCAM)
        }
        return null
    }

    /** Sub-category refinement for directory-matched senders. */
    private fun contentSubCategory(
        sender: String,
        body: String,
    ): SubCategory? =
        when {
            otpParser.parse(body) != null -> SubCategory.OTP
            transactionParser.parse(sender, body) != null -> SubCategory.TRANSACTION
            reminderParser.parse(sender, body) != null -> SubCategory.BILL
            scamDetector.isScam(body) -> SubCategory.SCAM
            else -> null
        }

    companion object {
        /** Key used for OTP codes in [CategorizationResult.extracted]. */
        const val EXTRACT_OTP_CODE = "otp_code"

        /**
         * Maximum number of characters of a message body that rule regexes
         * and content parsers are evaluated against. 1000 characters is ample
         * for transactional SMS (amount / account / OTP details arrive within
         * the first couple of segments); longer bodies are attacker-controlled
         * or promotional filler, and capping the evaluation input bounds the
         * regex engine's worst case. The full body is always persisted.
         */
        const val MAX_EVAL_BODY_LENGTH = 1000
    }
}
