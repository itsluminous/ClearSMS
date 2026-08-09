package app.clearsms.domain.parser

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.util.logging.Logger

/**
 * Named guards a RULE may reference through its `guards_none` clause
 * ("this rule does not apply if any listed guard matches the body").
 *
 * Two sources resolve, in order:
 * 1. the [GuardLibrary] - every parser guard id (`settled_payment`,
 *    `statement_notice`, ...) is referencable from rules as-is;
 * 2. the rule-guard extension document (`rules/rule_guards.json`, mirrored
 *    at `app/src/main/assets/guards/rule_guards.json`; identity enforced by
 *    a unit test) - guards that exist purely for rules, like `otp_mention`,
 *    which no Kotlin call site consults. Its id namespace is open: adding a
 *    guard here needs no code change, so shared negative knowledge (an OTP
 *    exclusion repeated across a hundred bank rules) lives in ONE editable
 *    entry.
 *
 * Loading, validation and evaluation reuse the guard library's discipline
 * exactly: load once, ReDoS-validate each pattern ([GuardLibrary.validate]),
 * skip-and-log anything unsafe or malformed (never fatal), cap the evaluated
 * input length and bound evaluation with the shared wall-clock budget.
 */
object RuleGuards {
    private val extensions: Map<String, List<Regex>> by lazy { parse(readResource()) }

    /** True when [id] names a known guard in either source. */
    fun isKnown(id: String): Boolean = GuardLibrary.guardFor(id) != null || extensions.containsKey(id)

    /**
     * True when the guard named [id] matches [text]. An unknown id never
     * matches (rules referencing one are already skipped at evaluation).
     */
    fun matches(
        id: String,
        text: String,
    ): Boolean {
        GuardLibrary.guardFor(id)?.let { return GuardLibrary.matches(it, text) }
        val patterns = extensions[id] ?: return false
        return GuardLibrary.anyMatch(id, patterns, text.take(GuardLibrary.MAX_INPUT_LENGTH))
    }

    /** Bundled document text, or null (with a warning) when missing. */
    internal fun readResource(): String? =
        try {
            RuleGuards::class.java.classLoader
                ?.getResourceAsStream(RESOURCE_NAME)
                ?.bufferedReader()
                ?.use { it.readText() }
                .also { if (it == null) warn("resource not found") }
        } catch (e: Exception) {
            warn(e.toString())
            null
        }

    /**
     * Parses and validates a rule-guards document. Never throws: a malformed
     * document yields no extension guards; an invalid/unsafe pattern or an
     * id shadowing a [GuardLibrary] guard is skipped with a warning.
     */
    internal fun parse(json: String?): Map<String, List<Regex>> {
        if (json == null) return emptyMap()
        val document =
            try {
                FORMAT.decodeFromString<RuleGuardsDocument>(json)
            } catch (e: Exception) {
                warn(e.toString())
                return emptyMap()
            }
        val result = mutableMapOf<String, List<Regex>>()
        for (entry in document.guards) {
            if (GuardLibrary.guardFor(entry.id) != null) {
                warn("guard '${entry.id}' shadows a guard library id; skipped")
                continue
            }
            result[entry.id] =
                entry.patterns.mapNotNull { pattern ->
                    val reason = GuardLibrary.validate(pattern)
                    if (reason != null) {
                        warn("guard '${entry.id}': pattern rejected ($reason)")
                        null
                    } else {
                        runCatching { Regex(pattern) }
                            .onFailure { warn("guard '${entry.id}': pattern does not compile") }
                            .getOrNull()
                    }
                }
        }
        return result
    }

    private fun warn(reason: String) {
        LOG.warning("Rule guards problem: $reason")
    }

    private val LOG = Logger.getLogger("ClearSMS")

    private const val RESOURCE_NAME = "rule_guards.json"

    private val FORMAT = Json { ignoreUnknownKeys = true }

    @Serializable
    private data class RuleGuardsDocument(
        val guards: List<RuleGuardEntry> = emptyList(),
    )

    @Serializable
    private data class RuleGuardEntry(
        val id: String,
        val description: String = "",
        val patterns: List<String> = emptyList(),
    )
}
