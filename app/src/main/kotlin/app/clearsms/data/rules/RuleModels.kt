package app.clearsms.data.rules

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Top-level rules document: `{"version": "1.0", "rules": [...]}`. */
@Serializable
data class RuleDocument(
    val version: String,
    val rules: List<RuleDefinition> = emptyList(),
)

/** A single categorization rule as found in bundled or user rules JSON. */
@Serializable
data class RuleDefinition(
    val id: String,
    val name: String? = null,
    val priority: Int = 0,
    val match: RuleMatch = RuleMatch(),
    val action: RuleAction,
    @SerialName("contributed_by") val contributedBy: String? = null,
    @SerialName("created_at") val createdAt: String? = null,
)

/** Matching conditions; all present conditions must hold for the rule to fire. */
@Serializable
data class RuleMatch(
    @SerialName("sender_pattern") val senderPattern: String? = null,
    @SerialName("body_pattern") val bodyPattern: String? = null,
    @SerialName("body_must_contain") val bodyMustContain: List<String> = emptyList(),
    @SerialName("body_must_not_contain") val bodyMustNotContain: List<String> = emptyList(),
)

/** Effect of a matching rule: target category and values to extract. */
@Serializable
data class RuleAction(
    val category: String,
    @SerialName("sub_category") val subCategory: String? = null,
    /** Values to extract; `$N` placeholders refer to `body_pattern` capture groups. */
    val extract: Map<String, String> = emptyMap(),
    /**
     * Explicit extract types, overriding the type inferred from the extract
     * key (see [RuleEngine]'s inference table). Values are one of `amount`,
     * `date`, `merchant`, `transaction_type`, `text`. Only needed where
     * inference is wrong or ambiguous — well-known keys such as `amount` or
     * `due_date` type themselves.
     */
    @SerialName("extract_types") val extractTypes: Map<String, String> = emptyMap(),
    val notification: String? = null,
)
