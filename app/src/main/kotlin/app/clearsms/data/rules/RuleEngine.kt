package app.clearsms.data.rules

import app.clearsms.domain.model.CategorizationResult
import app.clearsms.domain.model.Category
import app.clearsms.domain.model.ExtractedValue
import app.clearsms.domain.model.SubCategory
import app.clearsms.domain.model.TransactionType
import app.clearsms.domain.parser.ReminderParser
import app.clearsms.domain.parser.TransactionParser
import java.time.LocalDate
import java.util.concurrent.ConcurrentHashMap

/**
 * Evaluates categorization rules against a message.
 *
 * Rules are evaluated in descending priority order and the first matching rule
 * wins. A rule matches when ALL of its present conditions hold:
 * - `sender_pattern` regex matches the sender,
 * - `body_pattern` regex matches the body,
 * - every `body_must_contain` term occurs in the body (case-insensitive),
 * - no `body_must_not_contain` term occurs in the body (case-insensitive).
 *
 * Regexes are compiled once and cached; rules with invalid patterns are skipped
 * (and reported through [log]) so one bad community rule cannot break the engine.
 *
 * Extracts are resolved to TYPED values ([CategorizationResult.typed]): the
 * extract key infers the type (`amount` is an amount, `due_date` a date, ...),
 * a rule's `extract_types` map overrides the inference where it is wrong, and
 * the shared parsing algorithms — the amount grammar, the multi-format date
 * normalisation, merchant normalisation — are applied here, ONCE, instead of
 * ad hoc by every consumer. A capture that fails to parse as its type keeps
 * its raw string in [CategorizationResult.extracted] but yields no typed
 * value; a rule declaring an unknown type name is skipped and logged.
 *
 * Evaluation is additionally bounded by a per-message wall-clock budget
 * ([evaluationBudgetNanos]): user-imported and community rules run against
 * EVERY incoming message, so a single pathological (catastrophically
 * backtracking) pattern must degrade into a skipped rule set — never a hung
 * ingestion pipeline. When the budget is exceeded the engine logs the rule
 * that blew it and returns null, letting categorization fall through to the
 * next stage.
 */
class RuleEngine(
    private val log: (String) -> Unit = {},
    private val evaluationBudgetNanos: Long = DEFAULT_EVALUATION_BUDGET_NANOS,
    private val nanoTime: () -> Long = System::nanoTime,
    /** Shared date grammar ([ReminderParser.parseDate]); the algorithm stays in Kotlin. */
    private val dateParser: (String) -> LocalDate? = ReminderParser()::parseDate,
    /** Shared merchant normalizer ([TransactionParser.normalizeMerchantCandidate]). */
    private val merchantNormalizer: (String) -> String? = TransactionParser()::normalizeMerchantCandidate,
) {
    private val regexCache = ConcurrentHashMap<String, Any>()

    /**
     * Runs [rules] against the message and returns the result of the first
     * (highest-priority) matching rule, or null when nothing matches or the
     * evaluation budget is exhausted.
     */
    fun evaluate(
        rules: List<RuleDefinition>,
        sender: String,
        body: String,
    ): CategorizationResult? {
        val ordered = rules.sortedByDescending { it.priority }
        val start = nanoTime()
        for (rule in ordered) {
            val result = evaluateRule(rule, sender, body)
            if (result != null) return result
            if (nanoTime() - start > evaluationBudgetNanos) {
                log(
                    "Rule evaluation budget exceeded after rule '${rule.id}'; " +
                        "skipping remaining rules for this message",
                )
                return null
            }
        }
        return null
    }

    private fun evaluateRule(
        rule: RuleDefinition,
        sender: String,
        body: String,
    ): CategorizationResult? {
        val match = rule.match

        // A rule declaring an unknown extract type is malformed: skipped and
        // logged, never a crash (mirrors the invalid-regex handling).
        for ((key, name) in rule.action.extractTypes) {
            if (ExtractType.fromName(name) == null) {
                log("Skipping rule '${rule.id}': unknown extract type '$name' for '$key'")
                return null
            }
        }

        val senderRegex = match.senderPattern?.let { compiled(it, rule.id) ?: return null }
        if (senderRegex != null && !senderRegex.containsMatchIn(sender)) return null

        var bodyMatch: MatchResult? = null
        if (match.bodyPattern != null) {
            val bodyRegex = compiled(match.bodyPattern, rule.id) ?: return null
            bodyMatch = bodyRegex.find(body) ?: return null
        }

        if (match.bodyMustContain.any { !body.contains(it, ignoreCase = true) }) return null
        if (match.bodyMustNotContain.any { body.contains(it, ignoreCase = true) }) return null

        val (raw, typed) = resolveExtracts(rule, bodyMatch)
        return CategorizationResult(
            category = categoryOf(rule.action.category),
            subCategory = subCategoryOf(rule.action.subCategory),
            extracted = raw,
            typed = typed,
            matchedRuleId = rule.id,
        )
    }

    /**
     * Resolves `$N` placeholders against the body pattern's capture groups,
     * then types each value: the raw map holds every non-blank capture as
     * matched; the typed map holds the same keys parsed per their declared
     * or inferred [ExtractType], minus any value that failed to parse.
     */
    private fun resolveExtracts(
        rule: RuleDefinition,
        bodyMatch: MatchResult?,
    ): Pair<Map<String, String>, Map<String, ExtractedValue>> {
        val extract = rule.action.extract
        if (extract.isEmpty()) return emptyMap<String, String>() to emptyMap()
        val raw = LinkedHashMap<String, String>(extract.size)
        val typed = LinkedHashMap<String, ExtractedValue>(extract.size)
        for ((key, template) in extract) {
            val value =
                GROUP_REF_REGEX.replace(template) { ref ->
                    val index = ref.groupValues[1].toInt()
                    bodyMatch?.groupValues?.getOrNull(index) ?: ""
                }
            if (value.isBlank()) continue
            val trimmed = value.trim()
            raw[key] = trimmed
            val type = rule.action.extractTypes[key]?.let(ExtractType::fromName) ?: inferredType(key)
            val typedValue = typeValue(trimmed, type)
            if (typedValue == null) {
                log("Rule '${rule.id}': extract '$key' does not parse as ${type.jsonName}; typed value dropped")
            } else {
                typed[key] = typedValue
            }
        }
        return raw to typed
    }

    /** Parses [trimmed] as [type]; null when the value does not conform. */
    private fun typeValue(
        trimmed: String,
        type: ExtractType,
    ): ExtractedValue? =
        when (type) {
            ExtractType.AMOUNT ->
                trimmed
                    .replace(",", "")
                    .toDoubleOrNull()
                    ?.let { ExtractedValue.Amount(trimmed, it) }
            ExtractType.DATE -> dateParser(trimmed)?.let { ExtractedValue.Date(trimmed, it) }
            ExtractType.MERCHANT -> ExtractedValue.Merchant(trimmed, merchantNormalizer(trimmed))
            ExtractType.TRANSACTION_TYPE ->
                when (trimmed.lowercase()) {
                    "debit" -> TransactionType.DEBIT
                    "credit" -> TransactionType.CREDIT
                    else -> null
                }?.let { ExtractedValue.TxnType(trimmed, it) }
            ExtractType.TEXT -> ExtractedValue.Text(trimmed)
        }

    private fun compiled(
        pattern: String,
        ruleId: String,
    ): Regex? {
        val cached =
            regexCache.getOrPut(pattern) {
                try {
                    Regex(pattern)
                } catch (e: Exception) {
                    log("Skipping rule '$ruleId': invalid regex: ${e.message}")
                    INVALID
                }
            }
        return cached as? Regex
    }

    /** The kinds of thing a rule extract can be declared (or inferred) to be. */
    enum class ExtractType(
        val jsonName: String,
    ) {
        AMOUNT("amount"),
        DATE("date"),
        MERCHANT("merchant"),
        TRANSACTION_TYPE("transaction_type"),
        TEXT("text"),
        ;

        companion object {
            fun fromName(name: String): ExtractType? = entries.firstOrNull { it.jsonName == name }
        }
    }

    companion object {
        /**
         * Per-message evaluation budget across ALL rules (250 ms). Generous —
         * the full bundled set evaluates in single-digit milliseconds — but a
         * hard stop against a future rule with catastrophic backtracking.
         */
        const val DEFAULT_EVALUATION_BUDGET_NANOS: Long = 250_000_000L

        private val GROUP_REF_REGEX = Regex("\\$(\\d+)")

        /** Sentinel cached for patterns that failed to compile. */
        private val INVALID = Any()

        /**
         * Type inferred from a well-known extract key, keeping the terse
         * `"amount": "$1"` rule form self-typing. Unlisted keys are [ExtractType.TEXT].
         */
        private val INFERRED_TYPES =
            mapOf(
                "amount" to ExtractType.AMOUNT,
                "balance" to ExtractType.AMOUNT,
                "available_limit" to ExtractType.AMOUNT,
                "total_due" to ExtractType.AMOUNT,
                "min_due" to ExtractType.AMOUNT,
                "total_limit" to ExtractType.AMOUNT,
                "due_date" to ExtractType.DATE,
                "merchant" to ExtractType.MERCHANT,
                "type" to ExtractType.TRANSACTION_TYPE,
            )

        /** Declared-or-inferred type for an extract [key]. */
        fun inferredType(key: String): ExtractType = INFERRED_TYPES[key] ?: ExtractType.TEXT

        /** Maps a rule action category string to the [Category] enum. */
        fun categoryOf(value: String): Category =
            when (value.lowercase()) {
                "important" -> Category.IMPORTANT
                "promotional" -> Category.PROMOTIONAL
                // Legacy rule value: informational notices are IMPORTANT (no separate pill).
                "informational" -> Category.IMPORTANT
                "personal" -> Category.PERSONAL
                "otp" -> Category.OTP
                else -> Category.UNKNOWN
            }

        /** Maps a rule action sub_category string to the [SubCategory] enum. */
        fun subCategoryOf(value: String?): SubCategory? =
            when (value?.lowercase()) {
                null -> null
                "transaction" -> SubCategory.TRANSACTION
                "otp" -> SubCategory.OTP
                "bill" -> SubCategory.BILL
                "bank_alert" -> SubCategory.BANK_ALERT
                "government" -> SubCategory.GOVERNMENT
                "recharge" -> SubCategory.RECHARGE
                "investment" -> SubCategory.INVESTMENT
                "delivery" -> SubCategory.DELIVERY
                "offer" -> SubCategory.OFFER
                "scam" -> SubCategory.SCAM
                "fixed_deposit" -> SubCategory.FIXED_DEPOSIT
                "mutual_fund" -> SubCategory.MUTUAL_FUND
                "travel" -> SubCategory.TRAVEL
                "appointment" -> SubCategory.APPOINTMENT
                else -> SubCategory.GENERAL
            }
    }
}
