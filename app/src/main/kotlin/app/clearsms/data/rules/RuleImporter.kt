package app.clearsms.data.rules

import app.clearsms.data.db.RuleEntity
import kotlinx.serialization.json.Json

/** Parses a user-supplied rules JSON document into user rule rows. */
class RuleImporter(
    private val json: Json,
) {
    /**
     * Parses [text] as a rules document and returns the rows to insert.
     *
     * @throws IllegalArgumentException when the document cannot be parsed.
     */
    fun import(text: String): List<RuleEntity> {
        val document =
            try {
                json.decodeFromString(RuleDocument.serializer(), text)
            } catch (e: Exception) {
                throw IllegalArgumentException("Not a valid rules document", e)
            }
        return document.rules.map { it.toEntity(json, RuleSources.USER) }
    }
}
