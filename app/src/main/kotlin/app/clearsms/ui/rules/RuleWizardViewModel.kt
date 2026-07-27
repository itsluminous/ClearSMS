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
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.UUID
import javax.inject.Inject

/** Extraction targets the wizard can wire into the rule's `extract` block. */
enum class ExtractionField(
    val key: String,
) {
    OTP_CODE("otp_code"),
    AMOUNT("amount"),
    ACCOUNT_LAST4("account_last4"),
    MERCHANT("merchant"),
    BALANCE("balance"),
}

data class RuleWizardUiState(
    val name: String = "",
    val senderPattern: String = "",
    val bodyPattern: String = "",
    val category: String = "important",
    val subCategory: String? = null,
    /** field key → capture group reference ("$1"). */
    val extractions: Map<String, String> = emptyMap(),
    /** The message the wizard was opened from, used by the live test panel. */
    val testSender: String = "",
    val testBody: String = "",
    val testResult: CategorizationResult? = null,
    val testError: String? = null,
    val saved: Boolean = false,
)

@HiltViewModel
class RuleWizardViewModel
    @Inject
    constructor(
        savedStateHandle: SavedStateHandle,
        private val ruleRepository: RuleRepository,
        private val ruleEngine: RuleEngine,
        @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
    ) : ViewModel() {
        private val state: MutableStateFlow<RuleWizardUiState>

        init {
            val sender = savedStateHandle.get<String>("sender").orEmpty()
            val body = savedStateHandle.get<String>("body").orEmpty()
            state =
                MutableStateFlow(
                    RuleWizardUiState(
                        senderPattern = if (sender.isNotBlank()) RegexSuggester.suggestSenderPattern(sender) else "",
                        bodyPattern = if (body.isNotBlank()) RegexSuggester.suggestBodyPattern(body) else "",
                        testSender = sender,
                        testBody = body,
                    ),
                )
            runTest()
        }

        val uiState: StateFlow<RuleWizardUiState> = state.asStateFlow()

        fun onNameChange(value: String) = update { it.copy(name = value) }

        fun onSenderPatternChange(value: String) = update { it.copy(senderPattern = value) }

        fun onBodyPatternChange(value: String) = update { it.copy(bodyPattern = value) }

        fun onCategoryChange(value: String) = update { it.copy(category = value) }

        fun onSubCategoryChange(value: String?) = update { it.copy(subCategory = value) }

        fun onTestSenderChange(value: String) = update { it.copy(testSender = value) }

        fun onTestBodyChange(value: String) = update { it.copy(testBody = value) }

        fun toggleExtraction(field: ExtractionField) {
            update { current ->
                val extractions = current.extractions.toMutableMap()
                if (extractions.containsKey(field.key)) {
                    extractions.remove(field.key)
                } else {
                    extractions[field.key] = "$" + (extractions.size + 1)
                }
                current.copy(extractions = extractions)
            }
        }

        fun save() {
            val definition = buildDefinition() ?: return
            viewModelScope.launch(ioDispatcher) {
                ruleRepository.addUserRule(definition)
                state.value = state.value.copy(saved = true)
            }
        }

        private fun update(transform: (RuleWizardUiState) -> RuleWizardUiState) {
            state.value = transform(state.value)
            runTest()
        }

        /** Live test: evaluates the draft rule against the test message via the real engine. */
        private fun runTest() {
            val current = state.value
            val definition = buildDefinition()
            if (definition == null) {
                state.value = current.copy(testResult = null, testError = null)
                return
            }
            try {
                // Validate patterns eagerly so the panel can surface bad regex.
                current.senderPattern.takeIf { it.isNotBlank() }?.let { Regex(it) }
                current.bodyPattern.takeIf { it.isNotBlank() }?.let { Regex(it) }
            } catch (e: Exception) {
                state.value = current.copy(testResult = null, testError = e.message)
                return
            }
            val result = ruleEngine.evaluate(listOf(definition), current.testSender, current.testBody)
            state.value = current.copy(testResult = result, testError = null)
        }

        private fun buildDefinition(): RuleDefinition? {
            val current = state.value
            if (current.senderPattern.isBlank() && current.bodyPattern.isBlank()) return null
            return RuleDefinition(
                id = "user_" + UUID.randomUUID().toString().take(8),
                name = current.name.ifBlank { "My rule" },
                priority = USER_RULE_PRIORITY,
                match =
                    RuleMatch(
                        senderPattern = current.senderPattern.takeIf { it.isNotBlank() },
                        bodyPattern = current.bodyPattern.takeIf { it.isNotBlank() },
                    ),
                action =
                    RuleAction(
                        category = current.category,
                        subCategory = current.subCategory,
                        extract = current.extractions,
                    ),
            )
        }

        private companion object {
            /** User rules outrank all bundled rules (which top out well below this). */
            const val USER_RULE_PRIORITY = 1000
        }
    }
