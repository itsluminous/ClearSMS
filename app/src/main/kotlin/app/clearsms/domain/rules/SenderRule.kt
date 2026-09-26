package app.clearsms.domain.rules

import app.clearsms.data.repository.SenderNormalizer
import app.clearsms.data.rules.RuleAction
import app.clearsms.data.rules.RuleDefinition
import app.clearsms.data.rules.RuleMatch
import java.util.UUID

/**
 * The simplest rule there is: "every message from THIS sender is <category>".
 *
 * Built entirely by the app from a sender the user has already tapped - no
 * pattern field, no body matching, nothing to type. It is an ordinary
 * sender-bound [RuleDefinition] (same JSON, same table, same export/import,
 * same rule manager row), so nothing downstream learns a new rule type; and
 * because the app composes the pattern from a literal, none of the wizard's
 * pattern validation (compile, catch-all wrapper, capture groups, source
 * match) has anything to reject: a sender rule cannot fail to save.
 */
object SenderRule {
    /** Priority of every user-made rule: outranks every bundled rule (all < 1000). */
    const val USER_BAND_PRIORITY = 1001

    /** Categories a sender rule can assign, in display order. */
    val CATEGORIES: List<String> = listOf("important", "promotional", "personal", "otp", "unknown")

    /**
     * The sender core the rule is about: TRAI route prefix/suffix stripped
     * and upper-cased for alphanumeric ids (`VM-HDFCBK-S` → `HDFCBK`), the
     * last ten digits for phone numbers (`+91 98765 43210` → `9876543210`).
     * This is exactly [SenderNormalizer.normalize], i.e. the value the
     * message table indexes threads by, so the rule's pattern reaches every
     * route variant of the sender and the immediate re-sort finds them.
     */
    fun senderCore(sender: String): String = SenderNormalizer.normalize(sender)

    /**
     * Case-insensitive literal pattern for the sender core, with every regex
     * metacharacter escaped: a sender id such as `AB.CD` or `A+B` must match
     * itself and nothing else. Digits and letters need no escaping, so the
     * common case stays as readable as `(?i)HDFCBK`.
     */
    fun pattern(sender: String): String = "(?i)" + RuleComposer.escapeLiteral(senderCore(sender))

    /** True when the app-generated pattern for [sender] is a usable rule pattern. */
    fun canBuild(sender: String): Boolean = senderCore(sender).isNotBlank()

    /**
     * The rule for "messages from [sender] are always [category]".
     *
     * Priority sits in the user band ([USER_BAND_PRIORITY]) so the rule
     * outranks every bundled rule for that sender - which is the point:
     * the user is overriding what the community rules decided.
     */
    fun definition(
        sender: String,
        category: String,
        id: String = "user_" + UUID.randomUUID().toString().take(8),
    ): RuleDefinition {
        require(category in CATEGORIES) { "Unknown category '$category'" }
        val core = senderCore(sender)
        require(core.isNotBlank()) { "Sender is blank" }
        return RuleDefinition(
            id = id,
            name = "Always $category: $core",
            priority = USER_BAND_PRIORITY,
            match = RuleMatch(senderPattern = pattern(sender)),
            action = RuleAction(category = category),
        )
    }

    /** Whether an existing rule has the sender-only shape this object builds. */
    fun isSenderOnly(definition: RuleDefinition): Boolean =
        definition.match.senderPattern != null &&
            definition.match.bodyPattern == null &&
            definition.match.bodyMustContain.isEmpty() &&
            definition.match.bodyMustNotContain.isEmpty() &&
            definition.action.extract.isEmpty()
}
