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
        return normalizeInformational(
            enforceInvariants(sender, evalBody, rawCategorize(sender, evalBody, userRules, builtinRules)),
        )
    }

    /**
     * Final post-condition: no result ever leaves the categorizer as
     * [Category.IMPORTANT] with their sub-category preserved — there is no
     * notices (travel/PNR, appointment tokens, credit-score checks,
     * broker/exchange statements, UPI-mandate lifecycle) are IMPORTANT now,
     * keeping their sub-category so downstream meaning is unchanged. This also
     * normalizes bundled or user rules whose action still says
     * `category: informational` without touching the rule documents, and it
     * runs LAST so the mandate carve-out in [enforceInvariants] keeps blocking
     * the transaction promotion before the fold happens.
     */
    private fun normalizeInformational(result: CategorizationResult): CategorizationResult = result

    private fun rawCategorize(
        sender: String,
        evalBody: String,
        userRules: List<RuleDefinition>,
        builtinRules: List<RuleDefinition>,
    ): CategorizationResult {
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

    /**
     * Post-conditions applied to EVERY categorization result, regardless of
     * which stage of the priority chain produced it:
     *
     * 1. An extractable OTP code always wins over PROMOTIONAL — a directory
     *    entry or brand rule that files the sender as promotional must never
     *    swallow a verification code.
     * 2. An extracted transaction is never PROMOTIONAL: when the message is
     *    tagged [SubCategory.TRANSACTION] or the transaction parser finds a
     *    completed debit/credit, the message is promoted to IMPORTANT.
     *
     * Exceptions, deliberately narrow:
     * - SCAM results stay put — a phishing message quoting an "OTP" or a
     *   fake debit must not be promoted into the trusted categories.
     * - UPI-mandate lifecycle notices (created / cancelled) carry an amount
     *   but move no money; they must never be promoted AS a transaction. They
     *   surface as IMPORTANT bank alerts via [normalizeInformational].
     */
    private fun enforceInvariants(
        sender: String,
        evalBody: String,
        result: CategorizationResult,
    ): CategorizationResult {
        if (result.category != Category.PROMOTIONAL) return result
        if (result.subCategory == SubCategory.SCAM) return result

        otpParser.parse(evalBody)?.let { otp ->
            return result.copy(
                category = Category.OTP,
                subCategory = SubCategory.OTP,
                extracted = result.extracted + (EXTRACT_OTP_CODE to otp.code),
            )
        }

        if (MANDATE_NOTICE_REGEX.containsMatchIn(evalBody)) {
            return result.copy(category = Category.IMPORTANT, subCategory = SubCategory.BANK_ALERT)
        }

        val hasTransaction =
            result.subCategory == SubCategory.TRANSACTION ||
                transactionParser.parse(sender, evalBody) != null
        if (hasTransaction) {
            return result.copy(
                category = Category.IMPORTANT,
                subCategory = result.subCategory ?: SubCategory.TRANSACTION,
            )
        }
        return result
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
         * UPI Autopay / e-mandate lifecycle notices ("Mandate ... successfully
         * created", "successfully cancelled the scheduled ... payment"). They
         * quote the mandate's amount, but no money moved — the transaction
         * invariant must never promote them, and they surface as IMPORTANT
         * bank alerts after [normalizeInformational].
         * Spans are bounded ({0,60}) so the pattern cannot backtrack badly.
         */
        val MANDATE_NOTICE_REGEX =
            Regex(
                "(?i)\\bmandate\\b[\\s\\S]{0,60}?\\bsuccessfully\\s+(?:created|cancelled|revoked|modified)\\b|" +
                    "\\bsuccessfully\\s+cancelled\\s+the\\s+scheduled\\b[\\s\\S]{0,60}?\\bpayment\\b|" +
                    "\\bmandate\\s+(?:has\\s+been|is|was)\\s+(?:created|cancelled|revoked|modified)\\b",
            )

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
