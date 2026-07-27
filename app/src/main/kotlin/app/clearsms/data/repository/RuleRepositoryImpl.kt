package app.clearsms.data.repository

import app.clearsms.data.db.RuleDao
import app.clearsms.data.db.RuleEntity
import app.clearsms.data.rules.BundledRuleLoader
import app.clearsms.data.rules.RuleDefinition
import app.clearsms.data.rules.RuleExporter
import app.clearsms.data.rules.RuleImporter
import app.clearsms.data.rules.RuleSources
import app.clearsms.data.rules.toEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.json.Json

/** Default [RuleRepository] backed by Room and the bundled rules asset. */
class RuleRepositoryImpl(
    private val ruleDao: RuleDao,
    private val bundledRuleLoader: BundledRuleLoader,
    private val ruleImporter: RuleImporter,
    private val ruleExporter: RuleExporter,
    private val json: Json,
) : RuleRepository {
    override fun observeRules(): Flow<List<RuleEntity>> = ruleDao.observeAll()

    override val bundledRulesVersion: Flow<String?> = bundledRuleLoader.loadedVersionFlow

    override suspend fun ensureBundledRulesLoaded() {
        bundledRuleLoader.ensureLoaded()
    }

    override suspend fun addUserRule(definition: RuleDefinition) {
        ruleDao.insert(definition.toEntity(json, RuleSources.USER))
    }

    override suspend fun deleteRule(id: String) = ruleDao.deleteById(id)

    override suspend fun setRuleEnabled(
        id: String,
        enabled: Boolean,
    ) = ruleDao.setEnabled(id, enabled)

    override suspend fun exportUserRules(): String = ruleExporter.export(ruleDao.getBySource(RuleSources.USER))

    override suspend fun importRules(json: String) {
        // Imported ids are namespaced into the user id space: builtin rule
        // ids are public in the repository, so without the prefix a crafted
        // document could pick a builtin's id and silently overwrite that row
        // via the REPLACE insert strategy.
        val entities =
            ruleImporter.import(json).map { entity ->
                entity.copy(id = RuleEntity.namespacedUserId(entity.id))
            }
        ruleDao.insertAll(entities)
    }
}
