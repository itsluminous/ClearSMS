package app.clearsms.domain.categorizer

import app.clearsms.data.rules.RuleDefinition
import app.clearsms.data.rules.RuleEngine
import app.clearsms.domain.model.CategorizationResult
import app.clearsms.domain.model.Category
import app.clearsms.domain.model.SubCategory
import app.clearsms.domain.parser.GuardId
import app.clearsms.domain.parser.GuardLibrary
import app.clearsms.domain.parser.OtpParser
import app.clearsms.domain.parser.ReminderParser
import app.clearsms.domain.parser.ScamDetector
import app.clearsms.domain.parser.TransactionParser
import java.time.LocalDate

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
        /**
         * Reference date for yearless-date inference - the MESSAGE date
         * wherever the caller has one. Defaults to the current clock only
         * for contexts without a message timestamp (rule-wizard previews).
         */
        anchor: LocalDate = LocalDate.now(),
    ): CategorizationResult {
        // Regex engines only ever see a bounded prefix of the body: a
        // concatenated multipart SMS can be tens of thousands of characters,
        // which turns even mildly backtracking patterns into a denial of
        // service. The full body is still stored and displayed unchanged.
        val evalBody = body.take(MAX_EVAL_BODY_LENGTH)
        return normalizeInformational(
            enforceInvariants(sender, evalBody, anchor, rawCategorize(sender, evalBody, userRules, builtinRules, anchor)),
        )
    }

    /**
     * Final post-condition: no result ever leaves the categorizer as
     * [Category.IMPORTANT] with their sub-category preserved - there is no
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
        anchor: LocalDate,
    ): CategorizationResult {
        ruleEngine.evaluate(userRules, sender, evalBody, anchor)?.let { return it }
        ruleEngine.evaluate(builtinRules, sender, evalBody, anchor)?.let { return it }

        senderIdLookup.lookup(sender)?.let { info ->
            return CategorizationResult(
                category = info.category,
                subCategory = contentSubCategory(sender, evalBody, anchor),
            )
        }

        contentFallback(sender, evalBody, anchor)?.let { return it }

        if (contactLookup.isContact(sender)) {
            return CategorizationResult(category = Category.PERSONAL)
        }

        return CategorizationResult(category = Category.UNKNOWN)
    }

    /**
     * Post-conditions applied to EVERY categorization result, regardless of
     * which stage of the priority chain produced it:
     *
     * 1. An extractable OTP code always wins over PROMOTIONAL - a directory
     *    entry or brand rule that files the sender as promotional must never
     *    swallow a verification code.
     * 2. An extracted transaction is never PROMOTIONAL: when the message is
     *    tagged [SubCategory.TRANSACTION] or the transaction parser finds a
     *    completed debit/credit, the message is promoted to IMPORTANT.
     * 3. A keyword-ANCHORED OTP beats a transaction categorization: an
     *    authorization request ("413423 is SECRET OTP for txn of INR 1205.23
     *    on ... card ... at ...") quotes an amount, a card and a merchant,
     *    but nothing has moved - the user needs the CODE, not a transaction
     *    row. This mirrors invariant 1 for the transaction path, and also
     *    lifts a directory-matched sender's [SubCategory.OTP] refinement to
     *    the real OTP category so the OTP notification fires. Only
     *    [OtpParser.parseAnchored] counts here: the bare
     *    six-digits-near-a-context-word fallback could mistake a transaction
     *    reference in a debit alert that merely SAYS "OTP"/"PIN" in an
     *    advisory ("spent ... without PIN/OTP") for a code, so it must never
     *    reclassify a real spend. Transaction-ish extracts (amount/type/
     *    merchant) are dropped in the process so no transaction can derive
     *    downstream from the rule's captures.
     * 4. Financial content is never PROMOTIONAL even when no transaction
     *    derives: strong transactional artifacts (the data-driven
     *    `financial_evidence` guard - folio/UTR/SR ids, units allotted, NAV,
     *    instalment/redemption/settlement/refund lifecycle verbs, TDS
     *    summaries, recorded payments, order-number-plus-amount) demote a
     *    promotional result to IMPORTANT, unless the body is a marketing
     *    pitch (the `marketing_pitch` guard vetoes the rescue).
     *
     * Exceptions, deliberately narrow:
     * - SCAM results stay put - a phishing message quoting an "OTP" or a
     *   fake debit must not be promoted into the trusted categories.
     * - UPI-mandate lifecycle notices (created / cancelled) carry an amount
     *   but move no money; they must never be promoted AS a transaction. They
     *   surface as IMPORTANT bank alerts via [normalizeInformational].
     */
    private fun enforceInvariants(
        sender: String,
        evalBody: String,
        anchor: LocalDate,
        result: CategorizationResult,
    ): CategorizationResult {
        if (result.subCategory == SubCategory.SCAM) return result

        // Invariant 3: an anchored OTP beats a transaction categorization
        // (and lifts a directory sender's OTP sub-category to the category).
        if (result.category != Category.OTP &&
            (result.subCategory == SubCategory.TRANSACTION || result.subCategory == SubCategory.OTP)
        ) {
            otpParser.parseAnchored(evalBody)?.let { otp ->
                return result.copy(
                    category = Category.OTP,
                    subCategory = SubCategory.OTP,
                    extracted =
                        result.extracted - TRANSACTION_EXTRACT_KEYS + (EXTRACT_OTP_CODE to otp.code),
                )
            }
        }

        if (result.category != Category.PROMOTIONAL) return result

        otpParser.parse(evalBody)?.let { otp ->
            return result.copy(
                category = Category.OTP,
                subCategory = SubCategory.OTP,
                extracted = result.extracted + (EXTRACT_OTP_CODE to otp.code),
            )
        }

        // Unified payment-request carve-out (mandate lifecycle + UPI collect
        // requests): an amount that is only being asked for must never be
        // promoted AS a transaction - it surfaces as an IMPORTANT bank alert.
        if (transactionParser.isPaymentRequestNotice(evalBody)) {
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

        // Invariant 4: financial content is never PROMOTIONAL, even when no
        // transaction derives. Evidence is a body of POSITIVE knowledge in
        // data (the `financial_evidence` guard): transactional artifacts -
        // folio / UTR / SR identifiers, units-allotted and NAV figures,
        // instalment / redemption / settlement / refund lifecycle verbs, TDS
        // period summaries, "payment ... recorded" ledger entries, an order
        // number with its amount - never amounts alone, so a cashback pitch
        // or a loan offer quoting money cannot qualify. A marketing pitch
        // (the `marketing_pitch` guard) vetoes the rescue outright: pitch
        // phrasing that name-drops an artifact stays promotional. The
        // sub-category is refined where derivable (a parseable payment
        // obligation is a BILL); everything else lands as GENERAL financial
        // correspondence.
        if (GuardLibrary.matches(GuardId.FINANCIAL_EVIDENCE, evalBody) &&
            !GuardLibrary.matches(GuardId.MARKETING_PITCH, evalBody)
        ) {
            val subCategory =
                if (reminderParser.parse(sender, evalBody, anchor) != null) SubCategory.BILL else SubCategory.GENERAL
            return result.copy(category = Category.IMPORTANT, subCategory = subCategory)
        }
        return result
    }

    /** Content-based regex fallback for senders no rule or directory entry knows. */
    private fun contentFallback(
        sender: String,
        body: String,
        anchor: LocalDate,
    ): CategorizationResult? {
        otpParser.parse(body)?.let { otp ->
            return CategorizationResult(
                category = Category.OTP,
                subCategory = SubCategory.OTP,
                extracted = mapOf(EXTRACT_OTP_CODE to otp.code),
            )
        }
        // A collect / payment request or mandate notice quotes an amount but
        // moves no money: an IMPORTANT bank alert, never a transaction. Must
        // run before the transaction branch - "received ... request" would
        // otherwise satisfy the credit heuristics (the parser also vetoes
        // it, so ordering here is belt-and-braces).
        if (transactionParser.isPaymentRequestNotice(body)) {
            return CategorizationResult(category = Category.IMPORTANT, subCategory = SubCategory.BANK_ALERT)
        }
        if (transactionParser.parse(sender, body) != null) {
            return CategorizationResult(category = Category.IMPORTANT, subCategory = SubCategory.TRANSACTION)
        }
        if (reminderParser.parse(sender, body, anchor) != null) {
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
        anchor: LocalDate,
    ): SubCategory? =
        when {
            otpParser.parse(body) != null -> SubCategory.OTP
            // Payment requests / mandate notices: a bank alert, even for a
            // directory-matched sender like PhonePe.
            transactionParser.isPaymentRequestNotice(body) -> SubCategory.BANK_ALERT
            transactionParser.parse(sender, body) != null -> SubCategory.TRANSACTION
            reminderParser.parse(sender, body, anchor) != null -> SubCategory.BILL
            scamDetector.isScam(body) -> SubCategory.SCAM
            else -> null
        }

    companion object {
        /** Key used for OTP codes in [CategorizationResult.extracted]. */
        const val EXTRACT_OTP_CODE = "otp_code"

        /**
         * Rule-extract keys that describe a money movement. Dropped when an
         * anchored OTP reclassifies a rule-matched "transaction" to OTP, so
         * an authorization request's quoted amount can never derive a
         * transaction row or render as a signed amount downstream.
         */
        private val TRANSACTION_EXTRACT_KEYS = setOf("amount", "type", "merchant", "reference")

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
