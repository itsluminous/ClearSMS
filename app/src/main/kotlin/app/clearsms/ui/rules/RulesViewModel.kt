package app.clearsms.ui.rules

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.clearsms.data.repository.RuleRepository
import app.clearsms.data.rules.toDefinition
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
import kotlinx.coroutines.flow.flowOn
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
    /**
     * Legacy only: a rule disabled by an older version lives in preferences
     * as a `source|definitionJson` entry rather than as a disabled row. Kept
     * so such rules still show (and can be deleted) until
     * [RulesViewModel] has folded them into the table.
     */
    val parkedEntry: String? = null,
    /**
     * The row's stored JSON does not decode. The rule is shown in a
     * degraded form - name, id, delete - and cannot be opened, because the
     * only useful thing to do with it is remove it.
     */
    val malformed: Boolean = false,
) {
    /** Whether tapping the row can show anything: enabled, in the table, and decodable. */
    val canOpen: Boolean get() = enabled && parkedEntry == null && !malformed
}

/**
 * Read-only view of a rule's full definition, shown when a BUNDLED rule is
 * tapped. Bundled content is never edited in place - the bundled set must
 * stay identical to the shipped asset - so the only mutation offered is
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
    /** Legacy parked entries that could not be decoded and were skipped. */
    val unreadable: Int = 0,
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
            viewModelScope.launch(ioDispatcher) {
                ruleRepository.ensureBundledRulesLoaded()
                migrateParkedRules()
            }
        }

        val uiState: StateFlow<RulesUiState> =
            combine(
                ruleRepository.observeRules(),
                uiPrefs.disabledRules,
            ) { rules, disabled ->
                RulesUiStateBuilder.build(rules, disabled, json)
            }.flowOn(ioDispatcher)
                .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), RulesUiState())

        /**
         * Enabling/disabling flips the row's `enabled` flag in place (the
         * engine reads only enabled rows). One write to one store: the old
         * park-in-preferences-and-delete-the-row dance needed two writes to
         * two stores, and a process death between them left the rule both in
         * the table and in the parked set - a duplicate list key that
         * crashed this page on every open (issue #43). It also re-inserted a
         * re-enabled builtin as a USER rule, which the next reseed clobbered.
         */
        fun setEnabled(
            item: RuleItem,
            enabled: Boolean,
        ) {
            viewModelScope.launch(ioDispatcher) {
                val entry = item.parkedEntry
                if (entry != null) {
                    // Legacy parked rule not yet folded into the table: fold
                    // it now with the requested state, then drop the entry.
                    val (source, definition) = RulesUiStateBuilder.parkedDefinition(entry, json) ?: return@launch
                    ruleRepository.restoreParkedRule(definition, source, enabled)
                    uiPrefs.removeDisabledRule(entry)
                } else {
                    ruleRepository.setRuleEnabled(item.id, enabled)
                }
            }
        }

        /**
         * Deletes the row AND any legacy parked copy, so a rule the page
         * shows can always be removed whichever store it lives in.
         */
        fun deleteUserRule(id: String) {
            viewModelScope.launch(ioDispatcher) {
                ruleRepository.deleteRule(id)
                uiPrefs.disabledRules
                    .first()
                    .filter { RulesUiStateBuilder.parkedDefinition(it, json)?.second?.id == id }
                    .forEach { uiPrefs.removeDisabledRule(it) }
            }
        }

        /**
         * One-shot fold of legacy parked entries into the table as disabled
         * rows (original source preserved). An entry whose id already has a
         * row is stale - the row is what the engine sees - and is simply
         * dropped; an unreadable entry is left alone and counted in the UI.
         */
        private suspend fun migrateParkedRules() {
            val parked = uiPrefs.disabledRules.first()
            if (parked.isEmpty()) return
            val existing =
                ruleRepository
                    .observeRules()
                    .first()
                    .map { it.id }
                    .toSet()
            for (entry in parked) {
                val (source, definition) = RulesUiStateBuilder.parkedDefinition(entry, json) ?: continue
                if (definition.id !in existing) {
                    ruleRepository.restoreParkedRule(definition, source, enabled = false)
                }
                uiPrefs.removeDisabledRule(entry)
            }
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
                    entity?.toDefinition(json)?.let { definition ->
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
    }

/**
 * Case-insensitive rules search over name and id, so "hdfc" finds both
 * "HDFC Bank Debit Transaction" and `hdfc-debit-01`. A blank query keeps
 * everything (the pill is a filter, never a gate).
 */
fun filterRules(
    rules: List<RuleItem>,
    query: String,
): List<RuleItem> {
    val q = query.trim()
    if (q.isEmpty()) return rules
    return rules.filter { it.name.contains(q, ignoreCase = true) || it.id.contains(q, ignoreCase = true) }
}
