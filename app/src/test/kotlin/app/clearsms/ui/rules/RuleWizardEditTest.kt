package app.clearsms.ui.rules

import androidx.lifecycle.SavedStateHandle
import app.clearsms.data.rules.RuleAction
import app.clearsms.data.rules.RuleDefinition
import app.clearsms.data.rules.RuleEngine
import app.clearsms.data.rules.RuleMatch
import app.clearsms.data.rules.RuleSources
import app.clearsms.data.rules.toEntity
import app.clearsms.testing.FakeMessageRepository
import app.clearsms.testing.FakeRuleRepository
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Before
import org.junit.Test

/**
 * Edit and duplicate modes of the rule wizard: tapping a USER rule opens
 * the editor pre-filled and saving updates in place (same id, count
 * unchanged); duplicating a BUNDLED rule creates a user-owned copy with a
 * new id, and the bundled row itself is never touched.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class RuleWizardEditTest {
    private val dispatcher = StandardTestDispatcher()
    private val json = Json { ignoreUnknownKeys = true }

    private val userRule =
        RuleDefinition(
            id = "user_abc12345",
            name = "My HDFC rule",
            priority = 1001,
            match =
                RuleMatch(
                    senderPattern = ".*HDFCBK.*",
                    bodyPattern = "debited\\s+(?:INR|Rs\\.?)\\s*([\\d,]+)",
                    bodyMustContain = listOf("debited"),
                ),
            action = RuleAction(category = "important", subCategory = "transaction", extract = mapOf("amount" to "$1")),
        )

    private val bundledRule =
        RuleDefinition(
            id = "generic-debit",
            name = "Generic debit",
            priority = 100,
            match = RuleMatch(bodyPattern = "credited\\s+(?:INR|Rs\\.?)\\s*([\\d,]+)", bodyMustContain = listOf("credited")),
            action = RuleAction(category = "important", subCategory = "transaction", extract = mapOf("amount" to "$1")),
        )

    private lateinit var repository: FakeRuleRepository

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        repository =
            FakeRuleRepository(
                initial =
                    listOf(
                        bundledRule.toEntity(json, RuleSources.BUILTIN),
                        userRule.toEntity(json, RuleSources.USER),
                    ),
            )
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun viewModel(
        ruleId: String,
        duplicate: Boolean,
    ): RuleWizardViewModel =
        RuleWizardViewModel(
            savedStateHandle = SavedStateHandle(mapOf("ruleId" to ruleId, "duplicate" to duplicate)),
            messageRepository = FakeMessageRepository(),
            ruleRepository = repository,
            ruleEngine = RuleEngine(),
            json = json,
            ioDispatcher = dispatcher,
        )

    @Test
    fun `edit init pre-fills the wizard from the stored rule`() =
        runTest(dispatcher) {
            val vm = viewModel(ruleId = userRule.id, duplicate = false)
            advanceUntilIdle()
            val state = vm.uiState.value
            assertThat(state.editingRuleId).isEqualTo(userRule.id)
            assertThat(state.analyzed).isTrue()
            assertThat(state.name).isEqualTo("My HDFC rule")
            assertThat(state.category).isEqualTo("important")
            assertThat(state.subCategory).isEqualTo("transaction")
            assertThat(state.effectiveBodyPattern).isEqualTo(userRule.match.bodyPattern)
            assertThat(state.composedSenderPattern).isEqualTo(".*HDFCBK.*")
            assertThat(state.mustContain).containsExactly("debited")
            assertThat(state.extract).containsEntry("amount", "$1")
            assertThat(state.validationError).isNull()
        }

    @Test
    fun `edit save updates the rule in place - same id and unchanged count`() =
        runTest(dispatcher) {
            val vm = viewModel(ruleId = userRule.id, duplicate = false)
            advanceUntilIdle()
            val countBefore = repository.rules.value.size

            vm.onNameChange("Renamed rule")
            vm.onCategoryChange("promotional")
            vm.save()
            advanceUntilIdle()

            assertThat(vm.uiState.value.saved).isTrue()
            assertThat(repository.rules.value).hasSize(countBefore)
            val saved = repository.rules.value.single { it.id == userRule.id }
            assertThat(saved.name).isEqualTo("Renamed rule")
            assertThat(saved.isUserDefined).isTrue()
            assertThat(saved.actionJson).contains("promotional")
        }

    @Test
    fun `duplicate init pre-fills a copy without keeping the source id`() =
        runTest(dispatcher) {
            val vm = viewModel(ruleId = bundledRule.id, duplicate = true)
            advanceUntilIdle()
            val state = vm.uiState.value
            assertThat(state.editingRuleId).isNull()
            assertThat(state.name).isEqualTo("Generic debit (copy)")
            assertThat(state.effectiveBodyPattern).isEqualTo(bundledRule.match.bodyPattern)
            assertThat(state.priority).isEqualTo(DEFAULT_USER_PRIORITY.toString())
        }

    @Test
    fun `duplicate save creates a new user-owned rule and never touches the bundled row`() =
        runTest(dispatcher) {
            val bundledBefore = repository.rules.value.single { it.id == bundledRule.id }
            val vm = viewModel(ruleId = bundledRule.id, duplicate = true)
            advanceUntilIdle()

            vm.save()
            advanceUntilIdle()

            val bundledAfter = repository.rules.value.single { it.id == bundledRule.id }
            // The bundled rule must stay byte-identical to the shipped asset.
            assertThat(bundledAfter).isEqualTo(bundledBefore)

            val copy = repository.rules.value.single { it.name == "Generic debit (copy)" }
            assertThat(copy.id).isNotEqualTo(bundledRule.id)
            assertThat(copy.id).startsWith("user_")
            assertThat(copy.isUserDefined).isTrue()
            assertThat(copy.source).isEqualTo(RuleSources.USER)
        }

    @Test
    fun `create mode without ruleId is unaffected`() =
        runTest(dispatcher) {
            val vm =
                RuleWizardViewModel(
                    savedStateHandle = SavedStateHandle(mapOf("sender" to "HDFCBK", "body" to "Rs 500 debited from a/c")),
                    messageRepository = FakeMessageRepository(),
                    ruleRepository = repository,
                    ruleEngine = RuleEngine(),
                    json = json,
                    ioDispatcher = dispatcher,
                )
            advanceUntilIdle()
            val state = vm.uiState.value
            assertThat(state.editingRuleId).isNull()
            assertThat(state.analyzed).isTrue()
            assertThat(state.sourceBody).isEqualTo("Rs 500 debited from a/c")
        }
}
