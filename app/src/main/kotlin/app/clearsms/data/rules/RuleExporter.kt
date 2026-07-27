package app.clearsms.data.rules

import app.clearsms.data.db.RuleEntity
import kotlinx.serialization.json.Json

/** Serializes user rules into a shareable rules JSON document. */
class RuleExporter(
    private val json: Json,
) {
    fun export(
        rules: List<RuleEntity>,
        version: String = EXPORT_VERSION,
    ): String {
        val definitions = rules.mapNotNull { it.toDefinition(json) }
        return json.encodeToString(
            RuleDocument.serializer(),
            RuleDocument(version = version, rules = definitions),
        )
    }

    private companion object {
        const val EXPORT_VERSION = "1.0"
    }
}
