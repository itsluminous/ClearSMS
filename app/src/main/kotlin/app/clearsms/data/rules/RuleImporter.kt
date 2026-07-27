package app.clearsms.data.rules

import app.clearsms.data.db.RuleEntity
import kotlinx.serialization.json.Json

/**
 * Parses a user-supplied rules JSON document into user rule rows.
 *
 * Imported rules are persisted and then evaluated against EVERY incoming
 * message, so this is the trust boundary for community/user rule content:
 * documents with too many rules, oversized patterns, or patterns wrapped in a
 * leading `.*`/`[\s\S]*` (a known catastrophic-backtracking shape that is also
 * redundant, since the engine already searches anywhere in the body) are
 * rejected with a descriptive error before anything reaches the database.
 */
class RuleImporter(
    private val json: Json,
) {
    /**
     * Parses [text] as a rules document and returns the rows to insert.
     *
     * @throws IllegalArgumentException when the document cannot be parsed or
     * fails validation.
     */
    fun import(text: String): List<RuleEntity> {
        val document =
            try {
                json.decodeFromString(RuleDocument.serializer(), text)
            } catch (e: Exception) {
                throw IllegalArgumentException("Not a valid rules document", e)
            }
        require(document.rules.size <= MAX_RULES_PER_IMPORT) {
            "Too many rules: ${document.rules.size} (maximum $MAX_RULES_PER_IMPORT per import)"
        }
        for (rule in document.rules) {
            validatePattern(rule.id, rule.match.senderPattern)
            validatePattern(rule.id, rule.match.bodyPattern)
        }
        return document.rules.map { it.toEntity(json, RuleSources.USER) }
    }

    private fun validatePattern(
        ruleId: String,
        pattern: String?,
    ) {
        if (pattern == null) return
        require(pattern.length <= MAX_PATTERN_LENGTH) {
            "Rule '$ruleId': pattern is ${pattern.length} characters (maximum $MAX_PATTERN_LENGTH)"
        }
        require(!LEADING_WRAPPER.containsMatchIn(pattern)) {
            "Rule '$ruleId': pattern must not start with '.*' or '[\\s\\S]*' — " +
                "matching already searches anywhere in the message, and such wrappers " +
                "cause catastrophic regex backtracking"
        }
    }

    companion object {
        /** Upper bound on rules per imported document. */
        const val MAX_RULES_PER_IMPORT = 200

        /** Upper bound on a single regex pattern's length. */
        const val MAX_PATTERN_LENGTH = 512

        /**
         * A `.*` / `.+` / `[\s\S]*` style wrapper at the very start of the
         * pattern (optionally after inline flag groups like `(?i)`).
         */
        private val LEADING_WRAPPER =
            Regex("""^(?:\(\?[a-zA-Z]+\))*(?:\.[*+]|\[\\s\\S\][*+]|\[\\S\\s\][*+])""")
    }
}
