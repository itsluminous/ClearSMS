package app.clearsms.ui.rules

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.clearsms.data.repository.RuleRepository
import app.clearsms.data.rules.RuleAction
import app.clearsms.data.rules.RuleDefinition
import app.clearsms.data.rules.RuleEngine
import app.clearsms.data.rules.RuleMatch
import app.clearsms.di.IoDispatcher
import app.clearsms.domain.model.CategorizationResult
import app.clearsms.domain.rules.CapturePick
import app.clearsms.domain.rules.RuleComposer
import app.clearsms.domain.rules.RuleSuggester
import app.clearsms.domain.rules.SuggestedToken
import app.clearsms.domain.rules.TokenKind
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.UUID
import javax.inject.Inject

/** Sentinel "field" meaning a detected token is not captured. */
const val FIELD_IGNORE = "ignore"

/** Why the draft rule cannot be saved; mapped to actionable messages in the UI. */
enum class WizardValidationError {
    EMPTY_PATTERN,
    INVALID_PATTERN,
    CATCH_ALL_WRAPPER,
    DUPLICATE_FIELD,
    CAPTURE_MISMATCH,
    NO_SOURCE_MATCH,
}

data class RuleWizardUiState(
    // Step 1 — source message and detected tokens.
    val sourceSender: String = "",
    val sourceBody: String = "",
    val analyzed: Boolean = false,
    val tokens: List<SuggestedToken> = emptyList(),
    /** token index → extract field key or [FIELD_IGNORE]. */
    val tokenFields: Map<Int, String> = emptyMap(),
    // Step 2 — classification.
    val category: String = "important",
    val subCategory: String? = null,
    // Step 4 — conditions.
    val keywordOptions: List<String> = emptyList(),
    val mustContain: Set<String> = emptySet(),
    val mustNotContain: String = "",
    val bindSender: Boolean = true,
    // Step 5 — generated pattern + live testing.
    val composedSenderPattern: String = "",
    val composedBodyPattern: String = "",
    val patternOverride: String? = null,
    val extract: Map<String, String> = emptyMap(),
    val sourceResult: CategorizationResult? = null,
    val testSender: String = "",
    val testBody: String = "",
    val testResult: CategorizationResult? = null,
    // Step 6 — save.
    val name: String = "",
    val priority: String = DEFAULT_USER_PRIORITY.toString(),
    val validationError: WizardValidationError? = null,
    val saved: Boolean = false,
) {
    /** The pattern actually used: the advanced override when present. */
    val effectiveBodyPattern: String get() = patternOverride ?: composedBodyPattern
}

/** Default priority in the user band: outranks every bundled rule (< 1000). */
const val DEFAULT_USER_PRIORITY = 1001

@HiltViewModel
class RuleWizardViewModel
    @Inject
    constructor(
        savedStateHandle: SavedStateHandle,
        private val ruleRepository: RuleRepository,
        private val ruleEngine: RuleEngine,
        @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
    ) : ViewModel() {
        private val state = MutableStateFlow(RuleWizardUiState())
        val uiState: StateFlow<RuleWizardUiState> = state.asStateFlow()

        init {
            val sender = savedStateHandle.get<String>("sender").orEmpty()
            val body = savedStateHandle.get<String>("body").orEmpty()
            state.value = RuleWizardUiState(sourceSender = sender, sourceBody = body)
            if (body.isNotBlank()) analyze()
        }

        fun onSourceSenderChange(value: String) {
            if (!state.value.analyzed) state.value = state.value.copy(sourceSender = value)
        }

        fun onSourceBodyChange(value: String) {
            if (!state.value.analyzed) state.value = state.value.copy(sourceBody = value)
        }

        /** Runs the suggester over the source message and seeds every pick from it. */
        fun analyze() {
            val current = state.value
            if (current.sourceBody.isBlank()) return
            val tokens = RuleSuggester.suggest(current.sourceBody)
            val fields =
                tokens
                    .mapIndexedNotNull { index, token ->
                        if (token.kind == TokenKind.KEYWORD) null else index to (token.suggestedField ?: FIELD_IGNORE)
                    }.toMap()
            val keywords = tokens.filter { it.kind == TokenKind.KEYWORD }.map { it.literal }.distinct()
            update {
                it.copy(
                    analyzed = true,
                    tokens = tokens,
                    tokenFields = fields,
                    keywordOptions = keywords,
                    mustContain = keywords.take(DEFAULT_KEYWORD_PRESELECT).toSet(),
                    testSender = it.sourceSender,
                    testBody = it.sourceBody,
                )
            }
        }

        fun setTokenField(
            index: Int,
            field: String,
        ) = update { it.copy(tokenFields = it.tokenFields + (index to field)) }

        /** Chip/inline-highlight tap: toggles a token between captured and ignored. */
        fun toggleToken(index: Int) {
            val current = state.value
            val token = current.tokens.getOrNull(index) ?: return
            if (token.kind == TokenKind.KEYWORD) {
                toggleKeyword(token.literal)
                return
            }
            val now = current.tokenFields[index] ?: FIELD_IGNORE
            val next = if (now == FIELD_IGNORE) defaultFieldFor(token) else FIELD_IGNORE
            setTokenField(index, next)
        }

        fun toggleKeyword(word: String) =
            update {
                it.copy(mustContain = if (word in it.mustContain) it.mustContain - word else it.mustContain + word)
            }

        fun onMustNotContainChange(value: String) = update { it.copy(mustNotContain = value) }

        fun onBindSenderChange(value: Boolean) = update { it.copy(bindSender = value) }

        fun onCategoryChange(value: String) = update { it.copy(category = value) }

        fun onSubCategoryChange(value: String?) = update { it.copy(subCategory = value) }

        fun onNameChange(value: String) = update { it.copy(name = value) }

        fun onPriorityChange(value: String) = update { it.copy(priority = value.filter(Char::isDigit).take(6)) }

        fun onPatternOverrideChange(value: String) = update { it.copy(patternOverride = value) }

        fun resetPatternOverride() = update { it.copy(patternOverride = null) }

        fun onTestSenderChange(value: String) = update { it.copy(testSender = value) }

        fun onTestBodyChange(value: String) = update { it.copy(testBody = value) }

        fun save() {
            val current = state.value
            if (current.validationError != null) return
            val definition = buildDefinition(current) ?: return
            viewModelScope.launch(ioDispatcher) {
                ruleRepository.addUserRule(definition)
                state.value = state.value.copy(saved = true)
            }
        }

        /** Recomposes patterns, validation, and live source/test verdicts after every change. */
        private fun update(transform: (RuleWizardUiState) -> RuleWizardUiState) {
            val next = transform(state.value)
            val composed = RuleComposer.composeBody(next.sourceBody, picksOf(next))
            val recomposed =
                next.copy(
                    composedSenderPattern =
                        if (next.sourceSender.isBlank()) "" else RuleSuggester.senderPattern(next.sourceSender),
                    composedBodyPattern = composed.bodyPattern,
                    extract = composed.extract,
                )
            val error = validate(recomposed)
            val definition = if (error == null) buildDefinition(recomposed) else null
            state.value =
                recomposed.copy(
                    validationError = error,
                    sourceResult =
                        definition?.let {
                            ruleEngine.evaluate(listOf(it), recomposed.sourceSender, recomposed.sourceBody)
                        },
                    testResult =
                        definition?.takeIf { recomposed.testBody.isNotBlank() }?.let {
                            ruleEngine.evaluate(listOf(it), recomposed.testSender, recomposed.testBody)
                        },
                )
        }

        private fun picksOf(s: RuleWizardUiState): List<CapturePick> =
            s.tokenFields
                .entries
                .filter { it.value != FIELD_IGNORE }
                .mapNotNull { (index, field) -> s.tokens.getOrNull(index)?.let { CapturePick(it, field) } }

        private fun validate(s: RuleWizardUiState): WizardValidationError? {
            if (!s.analyzed) return WizardValidationError.EMPTY_PATTERN
            val body = s.effectiveBodyPattern
            if (body.isBlank() && s.composedSenderPattern.isBlank()) return WizardValidationError.EMPTY_PATTERN
            try {
                if (body.isNotBlank()) Regex(body)
                if (s.bindSender) Regex(s.composedSenderPattern)
            } catch (_: Exception) {
                return WizardValidationError.INVALID_PATTERN
            }
            if (RuleComposer.hasCatchAllWrapper(body)) return WizardValidationError.CATCH_ALL_WRAPPER
            val fields = s.tokenFields.values.filter { it != FIELD_IGNORE }
            if (fields.size != fields.distinct().size) return WizardValidationError.DUPLICATE_FIELD
            if (RuleComposer.maxGroupReference(s.extract) > RuleComposer.captureGroupCount(body)) {
                return WizardValidationError.CAPTURE_MISMATCH
            }
            val probe = buildDefinition(s) ?: return WizardValidationError.EMPTY_PATTERN
            if (ruleEngine.evaluate(listOf(probe), s.sourceSender, s.sourceBody) == null) {
                return WizardValidationError.NO_SOURCE_MATCH
            }
            return null
        }

        private fun buildDefinition(s: RuleWizardUiState): RuleDefinition? {
            val body = s.effectiveBodyPattern.takeIf { it.isNotBlank() }
            val sender = s.composedSenderPattern.takeIf { s.bindSender && it.isNotBlank() }
            if (body == null && sender == null) return null
            return RuleDefinition(
                id = "user_" + UUID.randomUUID().toString().take(8),
                name = s.name.ifBlank { "My rule" },
                priority = s.priority.toIntOrNull() ?: DEFAULT_USER_PRIORITY,
                match =
                    RuleMatch(
                        senderPattern = sender,
                        bodyPattern = body,
                        bodyMustContain = s.mustContain.toList(),
                        bodyMustNotContain =
                            s.mustNotContain
                                .split(',')
                                .map(String::trim)
                                .filter(String::isNotEmpty),
                    ),
                action =
                    RuleAction(
                        category = s.category,
                        subCategory = s.subCategory,
                        extract = s.extract,
                    ),
            )
        }

        private fun defaultFieldFor(token: SuggestedToken): String =
            token.suggestedField
                ?: when (token.kind) {
                    TokenKind.VENDOR -> RuleSuggester.Fields.MERCHANT
                    TokenKind.DATE -> RuleSuggester.Fields.DUE_DATE
                    else -> RuleSuggester.Fields.REFERENCE
                }

        private companion object {
            /** How many detected keywords are pre-selected as must-contain terms. */
            const val DEFAULT_KEYWORD_PRESELECT = 2
        }
    }
