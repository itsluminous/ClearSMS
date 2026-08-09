package app.clearsms.testing

import app.clearsms.data.db.RuleEntity
import app.clearsms.data.repository.RuleRepository
import app.clearsms.data.rules.RuleDefinition
import app.clearsms.data.rules.RuleSources
import app.clearsms.data.rules.toEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.serialization.json.Json

/**
 * In-memory [RuleRepository] mirroring the Room-backed implementation's
 * REPLACE-on-id insert semantics, so save-in-place behaviour is testable.
 */
class FakeRuleRepository(
    initial: List<RuleEntity> = emptyList(),
    private val json: Json = Json { ignoreUnknownKeys = true },
) : RuleRepository {
    val rules = MutableStateFlow(initial)

    override fun observeRules(): Flow<List<RuleEntity>> = rules

    override val bundledRulesVersion: Flow<String?> = MutableStateFlow("test")

    override suspend fun ensureBundledRulesLoaded() = Unit

    override suspend fun addUserRule(definition: RuleDefinition) {
        val entity = definition.toEntity(json, RuleSources.USER)
        rules.value = rules.value.filterNot { it.id == entity.id } + entity
    }

    override suspend fun deleteRule(id: String) {
        rules.value = rules.value.filterNot { it.id == id }
    }

    override suspend fun setRuleEnabled(
        id: String,
        enabled: Boolean,
    ) {
        rules.value = rules.value.map { if (it.id == id) it.copy(enabled = enabled) else it }
    }

    override suspend fun exportUserRules(): String = "{\"version\":\"1.0\",\"rules\":[]}"

    override suspend fun importRules(json: String) = Unit
}
