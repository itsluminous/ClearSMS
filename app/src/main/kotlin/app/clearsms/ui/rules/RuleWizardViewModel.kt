package app.clearsms.ui.rules

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.clearsms.data.repository.MessageRepository
import app.clearsms.data.repository.RuleRepository
import app.clearsms.data.rules.RuleAction
import app.clearsms.data.rules.RuleDefinition
import app.clearsms.data.rules.RuleEngine
import app.clearsms.data.rules.RuleMatch
import app.clearsms.data.rules.toDefinition
import app.clearsms.di.IoDispatcher
import app.clearsms.domain.model.CategorizationResult
import app.clearsms.domain.rules.CapturePick
import app.clearsms.domain.rules.RuleApplyScope
import app.clearsms.domain.rules.RuleComposer
import app.clearsms.domain.rules.RuleScopeResolver
import app.clearsms.domain.rules.RuleSuggester
import app.clearsms.domain.rules.SenderRule
import app.clearsms.domain.rules.SuggestedToken
import app.clearsms.domain.rules.TokenKind
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import java.util.UUID
import javax.inject.Inject

/** Sentinel "field" meaning a detected token is not captured. */
const val FIELD_IGNORE = "ignore"

/** The wizard step a validation problem belongs to, so the message can sit next to its cause. */
enum class WizardField {
    /** Step 0/1: the sample message. */
    SOURCE,

    /** Step 3: detected values → fields. */
    EXTRACT,

    /** Step 4: keywords, exclusions, sender binding. */
    CONDITIONS,

    /** Step 5: the (advanced) body pattern. */
    PATTERN,
}

/**
 * Why the draft rule cannot be saved. Every value names ONE thing that is
 * wrong and belongs to ONE step ([field]), so the UI can show the message at
 * the control that caused it - "the rule was rejected but I couldn't figure
 * out why" (issue #38) was a single generic line at the bottom of the screen.
 * [detail] on the state carries the specific word/reason the message quotes.
 */
enum class WizardValidationError(
    val field: WizardField,
) {
    /** No sample analyzed yet: nothing to build a rule from. */
    NEEDS_SAMPLE(WizardField.SOURCE),

    /** Neither a sender binding nor a body pattern: the rule would match everything. */
    NO_CONDITIONS(WizardField.CONDITIONS),

    /** The advanced body pattern is not valid regex; detail = the engine's reason. */
    INVALID_PATTERN(WizardField.PATTERN),

    /** The rule's stored sender pattern is not valid regex; detail = the engine's reason. */
    INVALID_SENDER_PATTERN(WizardField.CONDITIONS),

    /** The body pattern starts or ends with a `.*`-style wrapper. */
    CATCH_ALL_WRAPPER(WizardField.PATTERN),

    /** Two detected values map to the same field; detail = the field. */
    DUPLICATE_FIELD(WizardField.EXTRACT),

    /** Extraction references more groups than the pattern has; detail = "needed/have". */
    CAPTURE_MISMATCH(WizardField.PATTERN),

    /** Sender binding is on but the sender pattern does not match the sample's sender. */
    SENDER_NOT_MATCHING(WizardField.CONDITIONS),

    /** The body pattern does not match the sample text. */
    BODY_PATTERN_NOT_MATCHING(WizardField.PATTERN),

    /** A must-contain keyword is absent from the sample; detail = the word. */
    MUST_CONTAIN_MISSING(WizardField.CONDITIONS),

    /** A must-not-contain word is present in the sample; detail = the word. */
    MUST_NOT_CONTAIN_PRESENT(WizardField.CONDITIONS),

    /** The engine rejects the sample for a reason the checks above did not isolate. */
    NO_SOURCE_MATCH(WizardField.PATTERN),
}

data class RuleWizardUiState(
    /**
     * Edit mode: id of the rule being edited in place; null when creating
     * (or duplicating, which must mint a fresh user-owned id).
     */
    val editingRuleId: String? = null,
    /** Sender pattern loaded from an existing rule (no source message to compose from). */
    val senderPatternOverride: String? = null,
    /** Extract map loaded from an existing rule (no tokens to compose from). */
    val extractOverride: Map<String, String>? = null,
    /** Guard ids carried over verbatim from the loaded rule. */
    val guardsNone: List<String> = emptyList(),
    /** Explicit extract types carried over verbatim from the loaded rule. */
    val extractTypes: Map<String, String> = emptyMap(),
    /** Notification template carried over verbatim from the loaded rule. */
    val notificationAction: String? = null,
    // Step 1 - source message and detected tokens.
    val sourceSender: String = "",
    val sourceBody: String = "",
    val analyzed: Boolean = false,
    val tokens: List<SuggestedToken> = emptyList(),
    /** token index → extract field key or [FIELD_IGNORE]. */
    val tokenFields: Map<Int, String> = emptyMap(),
    // Step 2 - classification.
    val category: String = "important",
    val subCategory: String? = null,
    // Step 4 - conditions.
    val keywordOptions: List<String> = emptyList(),
    val mustContain: Set<String> = emptySet(),
    val mustNotContain: String = "",
    val bindSender: Boolean = true,
    // Step 5 - generated pattern + live testing.
    val composedSenderPattern: String = "",
    val composedBodyPattern: String = "",
    val patternOverride: String? = null,
    val extract: Map<String, String> = emptyMap(),
    val sourceResult: CategorizationResult? = null,
    val testSender: String = "",
    val testBody: String = "",
    val testResult: CategorizationResult? = null,
    // Step 6 - save.
    val name: String = "",
    val priority: String = DEFAULT_USER_PRIORITY.toString(),
    val validationError: WizardValidationError? = null,
    /** The word, field or regex reason the validation message quotes, when there is one. */
    val validationDetail: String? = null,
    /**
     * Set when Save was tapped while the rule was invalid. The FAB cannot be
     * disabled without hiding why, so the screen answers the tap with the
     * problem instead of silently doing nothing.
     */
    val saveBlocked: Boolean = false,
    val saved: Boolean = false,
    /**
     * Set once the rule is stored: whether it was applied to existing messages
     * straight away, or needs the user to run the full re-sort.
     */
    val applyOutcome: RuleApplyOutcome? = null,
) {
    /** The pattern actually used: the advanced override when present. */
    val effectiveBodyPattern: String get() = patternOverride ?: composedBodyPattern
}

/** Default priority in the user band: outranks every bundled rule (< 1000). */
const val DEFAULT_USER_PRIORITY = SenderRule.USER_BAND_PRIORITY

@HiltViewModel
class RuleWizardViewModel
    @Inject
    constructor(
        savedStateHandle: SavedStateHandle,
        private val ruleRepository: RuleRepository,
        private val messageRepository: MessageRepository,
        private val ruleEngine: RuleEngine,
        private val json: Json,
        @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
    ) : ViewModel() {
        private val state = MutableStateFlow(RuleWizardUiState())
        val uiState: StateFlow<RuleWizardUiState> = state.asStateFlow()

        init {
            val sender = savedStateHandle.get<String>("sender").orEmpty()
            val body = savedStateHandle.get<String>("body").orEmpty()
            val ruleId = savedStateHandle.get<String>("ruleId").orEmpty()
            val duplicate = savedStateHandle.get<Boolean>("duplicate") ?: false
            state.value = RuleWizardUiState(sourceSender = sender, sourceBody = body)
            if (ruleId.isNotBlank()) {
                loadExistingRule(ruleId, duplicate)
            } else if (body.isNotBlank()) {
                analyze()
            }
        }

        /**
         * Seeds the wizard from an existing rule. Editing keeps the rule's
         * id so saving updates it in place; duplicating drops the id so
         * saving creates a fresh user-owned copy and never touches the
         * original (bundled rules must stay identical to the shipped asset).
         */
        private fun loadExistingRule(
            ruleId: String,
            duplicate: Boolean,
        ) {
            viewModelScope.launch(ioDispatcher) {
                val definition =
                    ruleRepository
                        .observeRules()
                        .first()
                        .firstOrNull { it.id == ruleId }
                        ?.toDefinition(json) ?: return@launch
                val loadedName = definition.name ?: definition.id
                update {
                    RuleWizardUiState(
                        editingRuleId = if (duplicate) null else definition.id,
                        analyzed = true,
                        senderPatternOverride = definition.match.senderPattern,
                        patternOverride = definition.match.bodyPattern.orEmpty(),
                        extractOverride = definition.action.extract,
                        guardsNone = definition.match.guardsNone,
                        extractTypes = definition.action.extractTypes,
                        notificationAction = definition.action.notification,
                        category = definition.action.category,
                        subCategory = definition.action.subCategory,
                        keywordOptions = definition.match.bodyMustContain,
                        mustContain = definition.match.bodyMustContain.toSet(),
                        mustNotContain = definition.match.bodyMustNotContain.joinToString(", "),
                        bindSender = definition.match.senderPattern != null,
                        name = if (duplicate) "$loadedName (copy)" else loadedName,
                        priority =
                            (if (duplicate) DEFAULT_USER_PRIORITY else definition.priority).toString(),
                    )
                }
            }
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
            if (current.validationError != null) {
                state.value = current.copy(saveBlocked = true)
                return
            }
            val definition = buildDefinition(current) ?: return
            viewModelScope.launch(ioDispatcher) {
                ruleRepository.addUserRule(definition)
                // A saved rule that changes nothing on screen reads as broken,
                // so a sender-bound rule is applied to that sender's existing
                // messages at once. A rule that could match anything needs the
                // full re-sort, which the UI asks the user to run rather than
                // spending minutes of phone time unbidden.
                val outcome =
                    when (
                        val scope =
                            RuleScopeResolver.resolve(
                                senderPattern = current.composedSenderPattern,
                                sourceSender = current.sourceSender,
                                boundToSender = current.bindSender,
                                senderPatternEdited = current.senderPatternOverride != null,
                            )
                    ) {
                        is RuleApplyScope.Sender ->
                            RuleApplyOutcome.Applied(messageRepository.recategorizeSenderCore(scope.senderCore))
                        RuleApplyScope.Everything -> RuleApplyOutcome.NeedsFullResort
                    }
                state.value = state.value.copy(saved = true, applyOutcome = outcome)
            }
        }

        /** Recomposes patterns, validation, and live source/test verdicts after every change. */
        private fun update(transform: (RuleWizardUiState) -> RuleWizardUiState) {
            val next = transform(state.value)
            val composed = RuleComposer.composeBody(next.sourceBody, picksOf(next))
            val bodyPattern = next.patternOverride ?: composed.bodyPattern
            val recomposed =
                next.copy(
                    composedSenderPattern =
                        next.senderPatternOverride
                            ?: if (next.sourceSender.isBlank()) "" else RuleSuggester.senderPattern(next.sourceSender),
                    composedBodyPattern = composed.bodyPattern,
                    // No body pattern means no capture groups, so there is
                    // nothing to extract: a sender-only rule (the body pattern
                    // cleared under Advanced) must not be rejected for the
                    // extracts the sample analysis suggested.
                    extract = if (bodyPattern.isBlank()) emptyMap() else next.extractOverride ?: composed.extract,
                )
            val problem = validate(recomposed)
            val error = problem?.error
            val definition = if (error == null) buildDefinition(recomposed) else null
            state.value =
                recomposed.copy(
                    validationError = error,
                    validationDetail = problem?.detail,
                    // A fixed rule clears the "not saved" banner on its own.
                    saveBlocked = next.saveBlocked && error != null,
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

        /** A validation failure plus the specific thing the message should quote. */
        private data class Problem(
            val error: WizardValidationError,
            val detail: String? = null,
        )

        /**
         * Checks run in the order a user can fix them, and every failure names
         * the ONE condition that failed. A rule bound to a sender with no body
         * pattern (the one-tap sender rule, or the wizard with everything but
         * the sender switch cleared) passes every check by construction: the
         * app composed the pattern from a literal, so there is nothing here
         * that can reject it.
         */
        private fun validate(s: RuleWizardUiState): Problem? {
            if (!s.analyzed) return Problem(WizardValidationError.NEEDS_SAMPLE)
            val body = s.effectiveBodyPattern
            val senderBound = s.bindSender && s.composedSenderPattern.isNotBlank()
            if (body.isBlank() && !senderBound) return Problem(WizardValidationError.NO_CONDITIONS)
            val bodyRegex =
                if (body.isBlank()) {
                    null
                } else {
                    try {
                        Regex(body)
                    } catch (e: Exception) {
                        return Problem(WizardValidationError.INVALID_PATTERN, regexReason(e))
                    }
                }
            val senderRegex =
                if (!senderBound) {
                    null
                } else {
                    try {
                        Regex(s.composedSenderPattern)
                    } catch (e: Exception) {
                        return Problem(WizardValidationError.INVALID_SENDER_PATTERN, regexReason(e))
                    }
                }
            if (RuleComposer.hasCatchAllWrapper(body)) return Problem(WizardValidationError.CATCH_ALL_WRAPPER)
            val fields = s.tokenFields.values.filter { it != FIELD_IGNORE }
            fields.groupingBy { it }.eachCount().entries.firstOrNull { it.value > 1 }?.let {
                return Problem(WizardValidationError.DUPLICATE_FIELD, it.key)
            }
            val needed = RuleComposer.maxGroupReference(s.extract)
            val have = RuleComposer.captureGroupCount(body)
            if (needed > have) {
                return Problem(WizardValidationError.CAPTURE_MISMATCH, "$needed/$have")
            }
            // Editing an existing rule has no source message to match against.
            if (s.sourceBody.isBlank()) return null
            if (senderRegex != null && !senderRegex.containsMatchIn(s.sourceSender)) {
                return Problem(WizardValidationError.SENDER_NOT_MATCHING, s.sourceSender)
            }
            if (bodyRegex != null && bodyRegex.find(s.sourceBody) == null) {
                return Problem(WizardValidationError.BODY_PATTERN_NOT_MATCHING)
            }
            s.mustContain.firstOrNull { !s.sourceBody.contains(it, ignoreCase = true) }?.let {
                return Problem(WizardValidationError.MUST_CONTAIN_MISSING, it)
            }
            mustNotContainWords(s.mustNotContain).firstOrNull { s.sourceBody.contains(it, ignoreCase = true) }?.let {
                return Problem(WizardValidationError.MUST_NOT_CONTAIN_PRESENT, it)
            }
            val probe = buildDefinition(s) ?: return Problem(WizardValidationError.NO_CONDITIONS)
            if (ruleEngine.evaluate(listOf(probe), s.sourceSender, s.sourceBody) == null) {
                return Problem(WizardValidationError.NO_SOURCE_MATCH)
            }
            return null
        }

        /** The regex engine's own description of what is wrong, minus the pattern echo. */
        private fun regexReason(e: Exception): String =
            (e as? java.util.regex.PatternSyntaxException)?.description
                ?: e.message
                    ?.lineSequence()
                    ?.firstOrNull()
                    .orEmpty()

        private fun mustNotContainWords(raw: String): List<String> =
            raw
                .split(',')
                .map(String::trim)
                .filter(String::isNotEmpty)

        private fun buildDefinition(s: RuleWizardUiState): RuleDefinition? {
            val body = s.effectiveBodyPattern.takeIf { it.isNotBlank() }
            val sender = s.composedSenderPattern.takeIf { s.bindSender && it.isNotBlank() }
            if (body == null && sender == null) return null
            return RuleDefinition(
                // Editing keeps the id so the REPLACE insert updates in place.
                id = s.editingRuleId ?: ("user_" + UUID.randomUUID().toString().take(8)),
                name = s.name.ifBlank { "My rule" },
                priority = s.priority.toIntOrNull() ?: DEFAULT_USER_PRIORITY,
                match =
                    RuleMatch(
                        senderPattern = sender,
                        bodyPattern = body,
                        bodyMustContain = s.mustContain.toList(),
                        bodyMustNotContain = mustNotContainWords(s.mustNotContain),
                        guardsNone = s.guardsNone,
                    ),
                action =
                    RuleAction(
                        category = s.category,
                        subCategory = s.subCategory,
                        extract = s.extract,
                        extractTypes = s.extractTypes,
                        notification = s.notificationAction,
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

/** What happened to existing messages when a rule was saved. */
sealed interface RuleApplyOutcome {
    /** The rule was sender-bound: [messages] existing messages were re-sorted. */
    data class Applied(
        val messages: Int,
    ) : RuleApplyOutcome

    /** The rule could match anything, so the user has to run the full re-sort. */
    data object NeedsFullResort : RuleApplyOutcome
}
