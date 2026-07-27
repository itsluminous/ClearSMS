package app.clearsms.data.rules

import app.clearsms.data.db.RuleEntity
import kotlinx.serialization.json.Json
import java.time.Instant

/** Rule origins stored in [RuleEntity.source]. */
object RuleSources {
    const val BUILTIN = "builtin"
    const val USER = "user"
    const val COMMUNITY = "community"
}

/** Converts a parsed rule definition to its Room row form. */
fun RuleDefinition.toEntity(
    json: Json,
    source: String,
    nowMs: Long = System.currentTimeMillis(),
): RuleEntity =
    RuleEntity(
        id = id,
        name = name ?: id,
        priority = priority,
        matchJson = json.encodeToString(RuleMatch.serializer(), match),
        actionJson = json.encodeToString(RuleAction.serializer(), action),
        isUserDefined = source == RuleSources.USER,
        source = source,
        createdAt = parseCreatedAt(createdAt) ?: nowMs,
    )

/** Rebuilds the rule definition from a Room row; returns null on corrupt JSON. */
fun RuleEntity.toDefinition(json: Json): RuleDefinition? =
    try {
        RuleDefinition(
            id = id,
            name = name,
            priority = priority,
            match = json.decodeFromString(RuleMatch.serializer(), matchJson),
            action = json.decodeFromString(RuleAction.serializer(), actionJson),
            createdAt = Instant.ofEpochMilli(createdAt).toString(),
        )
    } catch (_: Exception) {
        null
    }

private fun parseCreatedAt(value: String?): Long? =
    value?.let {
        try {
            Instant.parse(it).toEpochMilli()
        } catch (_: Exception) {
            null
        }
    }
