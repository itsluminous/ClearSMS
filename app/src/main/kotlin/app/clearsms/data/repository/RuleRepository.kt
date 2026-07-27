package app.clearsms.data.repository

import app.clearsms.data.db.RuleEntity
import app.clearsms.data.rules.RuleDefinition
import kotlinx.coroutines.flow.Flow

/** Access to categorization rules (bundled and user-defined). */
interface RuleRepository {
    fun observeRules(): Flow<List<RuleEntity>>

    /** Version of the bundled rules document currently loaded, if any. */
    val bundledRulesVersion: Flow<String?>

    /** Loads the bundled rules into the database if not already current. */
    suspend fun ensureBundledRulesLoaded()

    suspend fun addUserRule(definition: RuleDefinition)

    suspend fun deleteRule(id: String)

    /** Serializes the user's own rules to a shareable JSON document. */
    suspend fun exportUserRules(): String

    /**
     * Imports rules from a JSON document as user rules.
     *
     * @throws IllegalArgumentException when the document cannot be parsed.
     */
    suspend fun importRules(json: String)
}
