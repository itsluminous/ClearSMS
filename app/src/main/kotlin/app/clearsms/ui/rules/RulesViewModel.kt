package app.clearsms.ui.rules

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.clearsms.data.db.RuleEntity
import app.clearsms.data.repository.RuleRepository
import app.clearsms.data.rules.RuleDefinition
import app.clearsms.di.IoDispatcher
import app.clearsms.ui.common.UiPrefs
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import javax.inject.Inject

/** One rule row: entity plus whether it is currently enabled. */
data class RuleItem(
    val id: String,
    val name: String,
    val isUserDefined: Boolean,
    val enabled: Boolean,
    /** For disabled rules: the parked definition JSON needed to re-enable. */
    val parkedEntry: String? = null,
)

/**
 * Read-only view of a rule's full definition, shown when a BUNDLED rule is
 * tapped. Bundled content is never edited in place — the bundled set must
 * stay identical to the shipped asset — so the only mutation offered is
 * "duplicate as my rule".
 */
data class RuleDetail(
    val id: String,
    val name: String,
    val priority: Int,
    val category: String,
    val subCategory: String?,
    val senderPattern: String?,
    val bodyPattern: String?,
    val mustContain: List<String>,
    val mustNotContain: List<String>,
    val guardsNone: List<String>,
    val extract: Map<String, String>,
    val isUserDefined: Boolean,
)

data class RulesUiState(
    val builtinRules: List<RuleItem> = emptyList(),
    val userRules: List<RuleItem> = emptyList(),
    val loaded: Boolean = false,
)

/** One-off UI events (export payloads, import outcomes). */
sealed interface RulesEvent {
    data class ExportReady(
        val json: String,
    ) : RulesEvent

    data class ShareReady(
        val json: String,
    ) : RulesEvent

    data class ImportFinished(
        val success: Boolean,
    ) : RulesEvent
}

@HiltViewModel
class RulesViewModel
    @Inject
    constructor(
        private val ruleRepository: RuleRepository,
        private val uiPrefs: UiPrefs,
        private val json: Json,
        @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
    ) : ViewModel() {
        private val events = MutableSharedFlow<RulesEvent>()
        val eventFlow: SharedFlow<RulesEvent> = events

        private val detail = MutableStateFlow<RuleDetail?>(null)

        /** Detail sheet for a tapped bundled rule; null when nothing is shown. */
        val ruleDetail: StateFlow<RuleDetail?> = detail.asStateFlow()

        init {
            viewModelScope.launch(ioDispatcher) { ruleRepository.ensureBundledRulesLoaded() }
        }

        val uiState: StateFlow<RulesUiState> =
            combine(
                ruleRepository.observeRules(),
                uiPrefs.disabledRules,
            ) { rules, disabled ->
                val disabledItems = disabled.mapNotNull(::parkedToItem)
                val active =
                    rules.map { rule ->
                        RuleItem(
                            id = rule.id,
                            name = rule.name,
                            isUserDefined = rule.isUserDefined,
                            enabled = true,
                        )
                    }
                val all = active + disabledItems
                RulesUiState(
                    builtinRules = all.filter { !it.isUserDefined }.sortedBy { it.name },
                    userRules = all.filter { it.isUserDefined }.sortedBy { it.name },
                    loaded = true,
                )
            }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), RulesUiState())

        /**
         * Disabling removes the rule from the engine's table and parks its
         * definition in preferences; enabling restores it.
         */
        fun setEnabled(
            item: RuleItem,
            enabled: Boolean,
        ) {
            viewModelScope.launch(ioDispatcher) {
                if (!enabled) {
                    val definition = findDefinition(item.id) ?: return@launch
                    val source = if (item.isUserDefined) "user" else "builtin"
                    uiPrefs.addDisabledRule("$source|" + json.encodeToString(RuleDefinition.serializer(), definition))
                    ruleRepository.deleteRule(item.id)
                } else {
                    val entry = item.parkedEntry ?: return@launch
                    val definition = json.decodeFromString(RuleDefinition.serializer(), entry.substringAfter('|'))
                    ruleRepository.addUserRule(definition)
                    uiPrefs.removeDisabledRule(entry)
                }
            }
        }

        fun deleteUserRule(id: String) {
            viewModelScope.launch(ioDispatcher) { ruleRepository.deleteRule(id) }
        }

        /** Opens the read-only detail view for the rule with [id]. */
        fun showDetail(id: String) {
            viewModelScope.launch(ioDispatcher) {
                val entity =
                    ruleRepository
                        .observeRules()
                        .first()
                        .firstOrNull { it.id == id }
                detail.value =
                    entity?.let(::entityToDefinition)?.let { definition ->
                        RuleDetail(
                            id = definition.id,
                            name = definition.name ?: definition.id,
                            priority = definition.priority,
                            category = definition.action.category,
                            subCategory = definition.action.subCategory,
                            senderPattern = definition.match.senderPattern,
                            bodyPattern = definition.match.bodyPattern,
                            mustContain = definition.match.bodyMustContain,
                            mustNotContain = definition.match.bodyMustNotContain,
                            guardsNone = definition.match.guardsNone,
                            extract = definition.action.extract,
                            isUserDefined = entity.isUserDefined,
                        )
                    }
            }
        }

        fun dismissDetail() {
            detail.value = null
        }

        fun export() {
            viewModelScope.launch(ioDispatcher) {
                events.emit(RulesEvent.ExportReady(ruleRepository.exportUserRules()))
            }
        }

        fun shareWithDeveloper() {
            viewModelScope.launch(ioDispatcher) {
                events.emit(RulesEvent.ShareReady(ruleRepository.exportUserRules()))
            }
        }

        fun import(text: String) {
            viewModelScope.launch(ioDispatcher) {
                val success =
                    try {
                        ruleRepository.importRules(text)
                        true
                    } catch (_: IllegalArgumentException) {
                        false
                    }
                events.emit(RulesEvent.ImportFinished(success))
            }
        }

        private suspend fun findDefinition(id: String): RuleDefinition? =
            ruleRepository
                .observeRules()
                .first()
                .firstOrNull { it.id == id }
                ?.let(::entityToDefinition)

        private fun entityToDefinition(entity: RuleEntity): RuleDefinition? =
            try {
                RuleDefinition(
                    id = entity.id,
                    name = entity.name,
                    priority = entity.priority,
                    match =
                        json.decodeFromString(
                            app.clearsms.data.rules.RuleMatch
                                .serializer(),
                            entity.matchJson,
                        ),
                    action =
                        json.decodeFromString(
                            app.clearsms.data.rules.RuleAction
                                .serializer(),
                            entity.actionJson,
                        ),
                )
            } catch (_: Exception) {
                null
            }

        private fun parkedToItem(entry: String): RuleItem? {
            val source = entry.substringBefore('|')
            val definition =
                try {
                    json.decodeFromString(RuleDefinition.serializer(), entry.substringAfter('|'))
                } catch (_: Exception) {
                    return null
                }
            return RuleItem(
                id = definition.id,
                name = definition.name ?: definition.id,
                isUserDefined = source == "user",
                enabled = false,
                parkedEntry = entry,
            )
        }
    }
