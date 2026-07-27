package app.clearsms.data.rules

import app.clearsms.domain.model.CategorizationResult
import app.clearsms.domain.model.Category
import app.clearsms.domain.model.SubCategory
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
 */
class RuleEngine(
    private val log: (String) -> Unit = {},
) {
    private val regexCache = ConcurrentHashMap<String, Any>()

    /**
     * Runs [rules] against the message and returns the result of the first
     * (highest-priority) matching rule, or null when nothing matches.
     */
    fun evaluate(
        rules: List<RuleDefinition>,
        sender: String,
        body: String,
    ): CategorizationResult? {
        val ordered = rules.sortedByDescending { it.priority }
        for (rule in ordered) {
            val result = evaluateRule(rule, sender, body)
            if (result != null) return result
        }
        return null
    }

    private fun evaluateRule(
        rule: RuleDefinition,
        sender: String,
        body: String,
    ): CategorizationResult? {
        val match = rule.match

        val senderRegex = match.senderPattern?.let { compiled(it, rule.id) ?: return null }
        if (senderRegex != null && !senderRegex.containsMatchIn(sender)) return null

        var bodyMatch: MatchResult? = null
        if (match.bodyPattern != null) {
            val bodyRegex = compiled(match.bodyPattern, rule.id) ?: return null
            bodyMatch = bodyRegex.find(body) ?: return null
        }

        if (match.bodyMustContain.any { !body.contains(it, ignoreCase = true) }) return null
        if (match.bodyMustNotContain.any { body.contains(it, ignoreCase = true) }) return null

        return CategorizationResult(
            category = categoryOf(rule.action.category),
            subCategory = subCategoryOf(rule.action.subCategory),
            extracted = resolveExtracts(rule.action.extract, bodyMatch),
            matchedRuleId = rule.id,
        )
    }

    /** Resolves `$N` placeholders against the body pattern's capture groups. */
    private fun resolveExtracts(
        extract: Map<String, String>,
        bodyMatch: MatchResult?,
    ): Map<String, String> {
        if (extract.isEmpty()) return emptyMap()
        val resolved = LinkedHashMap<String, String>(extract.size)
        for ((key, template) in extract) {
            val value =
                GROUP_REF_REGEX.replace(template) { ref ->
                    val index = ref.groupValues[1].toInt()
                    bodyMatch?.groupValues?.getOrNull(index) ?: ""
                }
            if (value.isNotBlank()) resolved[key] = value.trim()
        }
        return resolved
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

    companion object {
        private val GROUP_REF_REGEX = Regex("\\$(\\d+)")

        /** Sentinel cached for patterns that failed to compile. */
        private val INVALID = Any()

        /** Maps a rule action category string to the [Category] enum. */
        fun categoryOf(value: String): Category =
            when (value.lowercase()) {
                "important" -> Category.IMPORTANT
                "promotional" -> Category.PROMOTIONAL
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
                else -> SubCategory.GENERAL
            }
    }
}
