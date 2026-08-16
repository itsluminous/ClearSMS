package app.clearsms.domain.parser

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.util.logging.Logger

/**
 * Named guards consulted by the parsers and the categorizer.
 *
 * A guard is a body of NEGATIVE knowledge - "this phrasing means no money
 * moved", "this is an offer, not a statement" - whose pattern CONTENT is
 * community-editable data (`rules/guards.json`, mirrored at
 * `app/src/main/assets/guards/guards.json`; identity enforced by a unit
 * test). The SEMANTICS stay in Kotlin: where each guard is consulted, and
 * what happens when it fires (scrub / reject / suppress), is fixed at the
 * call sites and can never be changed by editing the data.
 */
enum class GuardId(
    val id: String,
) {
    /** Statement delivery notices - scrubbed before transaction parsing. */
    STATEMENT_NOTICE("statement_notice"),

    /** "Payment of INR X ... is due" - a future obligation, never a debit. */
    BILL_DUE_NOTICE("bill_due_notice"),

    /** Failed / declined payments - no money moved, never a transaction. */
    FAILED_PAYMENT("failed_payment"),

    /** Completed / settled / refunded events - never a reminder. */
    SETTLED_PAYMENT("settled_payment"),

    /** Investment / upsell pitch language - never a reminder. */
    MARKETING_PITCH("marketing_pitch"),

    /** Voucher / coupon / gift-card grants - never a reminder. */
    VOUCHER("voucher"),

    /** UPI mandate lifecycle notices - never promoted as a transaction. */
    MANDATE_NOTICE("mandate_notice"),

    /** UPI collect / payment requests - money asked for, never moved. */
    COLLECT_REQUEST("collect_request"),

    /** Retirement units-credited echoes of an already-recorded contribution. */
    RETIREMENT_UNITS_ECHO("retirement_units_echo"),

    /** Per-unit "every Rs X spent" pitch amounts - never a transaction. */
    HYPOTHETICAL_AMOUNT("hypothetical_amount"),

    /** Credit-limit increase offers - never a card's total limit. */
    LIMIT_OFFER("limit_offer"),

    /** "Premium" as a product tier - suppresses insurance evidence. */
    TIER_PREMIUM("tier_premium"),

    /** Future-tense window before a debit/credit keyword. */
    FUTURE_TENSE("future_tense"),

    /** Instruction verbs starting a would-be merchant capture. */
    INSTRUCTION_START("instruction_start"),
}

/**
 * Loads, validates and evaluates the bundled guard library.
 *
 * Loading mirrors [ParserTables]: once per process, from a classpath
 * resource, and it must never crash - a malformed or missing document, an
 * unknown guard id, or an invalid pattern degrades (with a logged warning)
 * to a guard that never matches, so a bad community edit can at worst lose
 * a veto, never the app.
 *
 * Guard patterns run against EVERY incoming SMS, so validation applies the
 * rule-engine's ReDoS discipline at load time ([validate]): oversized
 * patterns, leading/trailing `.*`/`[\s\S]*` wrappers (the shape behind a
 * past 423-second catastrophic-backtracking incident), nested unbounded
 * quantifiers and variable-length lookbehinds are all rejected - skipped
 * and logged, never fatal. Evaluation is additionally bounded by the same
 * wall-clock budget the rule engine uses (checked between patterns) and by
 * the categorizer's input-length cap.
 */
object GuardLibrary {
    private val guards: Map<GuardId, List<Regex>> by lazy { parse(readResource()) }

    private val byId: Map<String, GuardId> = GuardId.entries.associateBy { it.id }

    /** [GuardId] for a data-referenced guard id string, or null when unknown. */
    fun guardFor(id: String): GuardId? = byId[id]

    /** True when any of [id]'s patterns matches [text] (bounded input/budget). */
    fun matches(
        id: GuardId,
        text: String,
    ): Boolean = anyMatch(id.id, guards[id].orEmpty(), text.take(MAX_INPUT_LENGTH))

    /**
     * [text] with every span matched by [id]'s patterns replaced by a single
     * space (the statement-notice "scrub before parsing" semantic). Patterns
     * are applied in declaration order.
     */
    fun scrub(
        id: GuardId,
        text: String,
    ): String = guards[id].orEmpty().fold(text) { acc, regex -> regex.replace(acc, " ") }

    /** Any-pattern match with the evaluation budget enforced between patterns. */
    internal fun anyMatch(
        id: String,
        patterns: List<Regex>,
        text: String,
        budgetNanos: Long = EVALUATION_BUDGET_NANOS,
        nanoTime: () -> Long = System::nanoTime,
    ): Boolean {
        val start = nanoTime()
        for (regex in patterns) {
            if (regex.containsMatchIn(text)) {
                // Attribution, id only - message content never reaches logs.
                LOG.fine("guard $id hit")
                return true
            }
            if (nanoTime() - start > budgetNanos) {
                LOG.warning("guard $id exceeded its evaluation budget; skipping remaining patterns")
                return false
            }
        }
        return false
    }

    /** Bundled document text, or null (with a warning) when missing. */
    internal fun readResource(): String? =
        try {
            GuardLibrary::class.java.classLoader
                ?.getResourceAsStream(RESOURCE_NAME)
                ?.bufferedReader()
                ?.use { it.readText() }
                .also { if (it == null) warn("resource not found") }
        } catch (e: Exception) {
            warn(e.toString())
            null
        }

    /**
     * Parses and validates a guards document. Never throws: a malformed
     * document yields an empty library (every guard never matches); an
     * unknown id or an invalid/unsafe pattern is skipped with a warning.
     */
    internal fun parse(json: String?): Map<GuardId, List<Regex>> {
        if (json == null) return emptyMap()
        val document =
            try {
                FORMAT.decodeFromString<GuardsDocument>(json)
            } catch (e: Exception) {
                warn(e.toString())
                return emptyMap()
            }
        val byId = GuardId.entries.associateBy { it.id }
        val result = mutableMapOf<GuardId, List<Regex>>()
        for (entry in document.guards) {
            val id = byId[entry.id]
            if (id == null) {
                warn("unknown guard id '${entry.id}' skipped")
                continue
            }
            result[id] =
                entry.patterns.mapNotNull { pattern ->
                    val reason = validate(pattern)
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
        GuardId.entries.filter { it !in result }.forEach { warn("guard '${it.id}' missing from document; it will never match") }
        return result
    }

    /**
     * Load-time safety validation; returns the rejection reason or null when
     * the pattern is acceptable. Same discipline as the rule importer, plus
     * the shapes it bans only by convention:
     * - length cap ([MAX_PATTERN_LENGTH], shared with the rule importer);
     * - leading or trailing `.*` / `.+` / `[\s\S]*` wrappers - redundant
     *   (matching already searches anywhere) and catastrophic under
     *   backtracking;
     * - unbounded quantifier applied to a group that itself contains an
     *   unbounded quantifier (the classic `(a+)+` explosion);
     * - lookbehind containing a quantifier (variable-length lookbehind).
     */
    internal fun validate(pattern: String): String? {
        if (pattern.isBlank()) return "blank pattern"
        if (pattern.length > MAX_PATTERN_LENGTH) {
            return "pattern is ${pattern.length} characters (maximum $MAX_PATTERN_LENGTH)"
        }
        if (LEADING_WRAPPER.containsMatchIn(pattern)) return "leading .*/[\\s\\S]* wrapper"
        if (TRAILING_WRAPPER.containsMatchIn(pattern)) return "trailing .*/[\\s\\S]* wrapper"
        if (VARIABLE_LOOKBEHIND.containsMatchIn(pattern)) return "variable-length lookbehind"
        if (hasNestedUnboundedQuantifier(pattern)) return "nested unbounded quantifier"
        return null
    }

    /**
     * Detects an unbounded quantifier (`*`, `+`, `{n,}`) applied to a group
     * whose body contains an unbounded quantifier - the catastrophic
     * backtracking shape. Escapes and character classes are skipped, so
     * `[\w&+.]+` (a literal `+` inside a class) is not a false positive;
     * bounded repetitions (`{0,60}`, `?`) never count as unbounded.
     */
    internal fun hasNestedUnboundedQuantifier(pattern: String): Boolean {
        // Per open group: does its body (so far) contain an unbounded quantifier?
        val groupHasUnbounded = ArrayDeque<Boolean>()
        var i = 0
        var lastClosedHadUnbounded = false

        fun markUnbounded() {
            for (depth in groupHasUnbounded.indices) groupHasUnbounded[depth] = true
        }

        while (i < pattern.length) {
            when (pattern[i]) {
                '\\' -> i++ // skip the escaped char
                '[' -> {
                    // Skip the whole character class (quantifiers inside are literals).
                    i++
                    if (i < pattern.length && pattern[i] == '^') i++
                    if (i < pattern.length && pattern[i] == ']') i++
                    while (i < pattern.length && pattern[i] != ']') {
                        if (pattern[i] == '\\') i++
                        i++
                    }
                }
                '(' -> groupHasUnbounded.addLast(false)
                ')' -> lastClosedHadUnbounded = groupHasUnbounded.removeLastOrNull() ?: false
                '*', '+' -> {
                    if (i > 0 && pattern[i - 1] == ')' && lastClosedHadUnbounded) return true
                    markUnbounded()
                }
                '{' -> {
                    // Only `{n,}` (no upper bound) is unbounded.
                    val end = pattern.indexOf('}', i)
                    if (end > i && UNBOUNDED_BRACE.matches(pattern.substring(i, end + 1))) {
                        if (i > 0 && pattern[i - 1] == ')' && lastClosedHadUnbounded) return true
                        markUnbounded()
                    }
                    if (end > i) i = end
                }
            }
            i++
        }
        return false
    }

    private fun warn(reason: String) {
        LOG.warning("Guard library problem: $reason")
    }

    private val LOG = Logger.getLogger("ClearSMS")

    private const val RESOURCE_NAME = "guards.json"

    /**
     * Input-length cap for [matches], mirroring the categorizer's
     * MAX_EVAL_BODY_LENGTH: guards must never see an unbounded body.
     */
    internal const val MAX_INPUT_LENGTH = 1000

    /** Shared with the rule importer's MAX_PATTERN_LENGTH. */
    internal const val MAX_PATTERN_LENGTH = 512

    /** Same per-consultation budget as the rule engine (250 ms). */
    internal const val EVALUATION_BUDGET_NANOS: Long = 250_000_000L

    /** `.*` / `.+` / `[\s\S]*` at the very start (after inline flag groups). */
    private val LEADING_WRAPPER =
        Regex("""^(?:\(\?[a-zA-Z]+\))*(?:\.[*+]|\[\\s\\S\][*+]|\[\\S\\s\][*+])""")

    /** `.*` / `.+` / `[\s\S]*` at the very end (optionally before `$`). */
    private val TRAILING_WRAPPER =
        Regex("""(?:\.[*+]|\[\\s\\S\][*+]|\[\\S\\s\][*+])\??\$?$""")

    /** A lookbehind whose body contains any quantifier. */
    private val VARIABLE_LOOKBEHIND = Regex("""\(\?<[=!][^)]*[*+{]""")

    /** `{n,}` - an unbounded counted repetition. */
    private val UNBOUNDED_BRACE = Regex("""\{\d+,\}""")

    private val FORMAT = Json { ignoreUnknownKeys = true }

    @Serializable
    private data class GuardsDocument(
        val guards: List<GuardEntry> = emptyList(),
    )

    @Serializable
    private data class GuardEntry(
        val id: String,
        val description: String = "",
        val patterns: List<String> = emptyList(),
    )
}
