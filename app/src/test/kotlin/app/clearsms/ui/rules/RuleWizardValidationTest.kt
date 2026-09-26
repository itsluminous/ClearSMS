package app.clearsms.ui.rules

import androidx.lifecycle.SavedStateHandle
import app.clearsms.data.rules.RuleEngine
import app.clearsms.domain.rules.RuleSuggester
import app.clearsms.domain.rules.SenderRule
import app.clearsms.domain.rules.TokenKind
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
import java.io.File

/**
 * "The rule was rejected, but I couldn't figure out why" (issue #38): every
 * way the wizard can refuse a rule now names the ONE condition that failed,
 * carries the specific word/field/reason to quote, belongs to the step whose
 * control caused it, and a Save tap on an invalid rule answers with the
 * problem instead of silently doing nothing. A rule bound to just the sender
 * has nothing these checks can reject.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class RuleWizardValidationTest {
    private val dispatcher = StandardTestDispatcher()
    private val json = Json { ignoreUnknownKeys = true }
    private val sender = "VM-HDFCBK-S"
    private val body = "Rs.500 debited from a/c XX1234 on 12-Jan-24. Ref 998877. Avl bal Rs.2,500"

    private lateinit var rules: FakeRuleRepository
    private lateinit var messages: FakeMessageRepository

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        rules = FakeRuleRepository()
        messages = FakeMessageRepository()
    }

    @After
    fun tearDown() = Dispatchers.resetMain()

    private fun viewModel(sourceBody: String = body) =
        RuleWizardViewModel(
            savedStateHandle = SavedStateHandle(mapOf("sender" to sender, "body" to sourceBody)),
            ruleRepository = rules,
            messageRepository = messages,
            ruleEngine = RuleEngine(),
            json = json,
            ioDispatcher = dispatcher,
        )

    @Test
    fun `a message-built rule starts valid`() {
        val state = viewModel().uiState.value
        assertThat(state.analyzed).isTrue()
        assertThat(state.validationError).isNull()
    }

    @Test
    fun `sender-only rule - no body pattern, just the sender - cannot be rejected for any category`() =
        runTest(dispatcher) {
            for (category in SenderRule.CATEGORIES) {
                val vm = viewModel()
                vm.onPatternOverrideChange("")
                vm.onCategoryChange(category)
                // Whatever keywords were pre-selected are dropped too: the rule is the sender alone.
                vm.uiState.value.mustContain
                    .forEach(vm::toggleKeyword)
                val state = vm.uiState.value
                assertThat(state.effectiveBodyPattern).isEmpty()
                assertThat(state.bindSender).isTrue()
                assertThat(state.validationError).isNull()

                vm.save()
                advanceUntilIdle()
                // ...and it takes the immediate path, not the full re-sort dialog.
                assertThat(vm.uiState.value.applyOutcome).isInstanceOf(RuleApplyOutcome.Applied::class.java)
                assertThat(messages.recategorizedSenderCores.last()).isEqualTo("HDFCBK")
            }
        }

    @Test
    fun `no conditions at all is named as such and points at the sender switch`() {
        val vm = viewModel()
        vm.onPatternOverrideChange("")
        vm.onBindSenderChange(false)

        val state = vm.uiState.value
        assertThat(state.validationError).isEqualTo(WizardValidationError.NO_CONDITIONS)
        assertThat(state.validationError?.field).isEqualTo(WizardField.CONDITIONS)
    }

    @Test
    fun `a broken pattern quotes the regex engine's reason and belongs to the pattern step`() {
        val vm = viewModel()
        vm.onPatternOverrideChange("debited (Rs")

        val state = vm.uiState.value
        assertThat(state.validationError).isEqualTo(WizardValidationError.INVALID_PATTERN)
        assertThat(state.validationError?.field).isEqualTo(WizardField.PATTERN)
        assertThat(state.validationDetail).isEqualTo("Unclosed group")
    }

    @Test
    fun `a catch-all wrapper is rejected as before - pattern safety is not weakened`() {
        val vm = viewModel()
        vm.onPatternOverrideChange(".*debited.*")

        assertThat(vm.uiState.value.validationError).isEqualTo(WizardValidationError.CATCH_ALL_WRAPPER)
        assertThat(
            vm.uiState.value.validationError
                ?.field,
        ).isEqualTo(WizardField.PATTERN)
    }

    @Test
    fun `two values on one field names the field and belongs to the extraction step`() {
        val vm = viewModel()
        val valueTokens =
            vm.uiState.value.tokens
                .withIndex()
                .filter { it.value.kind != TokenKind.KEYWORD }
        assertThat(valueTokens.size).isAtLeast(2)
        vm.setTokenField(valueTokens[0].index, RuleSuggester.Fields.AMOUNT)
        vm.setTokenField(valueTokens[1].index, RuleSuggester.Fields.AMOUNT)

        val state = vm.uiState.value
        assertThat(state.validationError).isEqualTo(WizardValidationError.DUPLICATE_FIELD)
        assertThat(state.validationError?.field).isEqualTo(WizardField.EXTRACT)
        assertThat(state.validationDetail).isEqualTo(RuleSuggester.Fields.AMOUNT)
    }

    @Test
    fun `too few capture groups reports needed versus available`() {
        val vm = viewModel()
        // The composed rule extracts at least one value; a hand pattern with no groups cannot feed it.
        assertThat(vm.uiState.value.extract).isNotEmpty()
        vm.onPatternOverrideChange("debited")

        val state = vm.uiState.value
        assertThat(state.validationError).isEqualTo(WizardValidationError.CAPTURE_MISMATCH)
        assertThat(state.validationError?.field).isEqualTo(WizardField.PATTERN)
        assertThat(state.validationDetail).endsWith("/0")
    }

    @Test
    fun `an excluded word that the sample contains is quoted and belongs to the conditions step`() {
        val vm = viewModel()
        vm.onMustNotContainChange("offer, debited")

        val state = vm.uiState.value
        assertThat(state.validationError).isEqualTo(WizardValidationError.MUST_NOT_CONTAIN_PRESENT)
        assertThat(state.validationError?.field).isEqualTo(WizardField.CONDITIONS)
        assertThat(state.validationDetail).isEqualTo("debited")
    }

    @Test
    fun `a pattern that compiles but misses the sample is told apart from a broken one`() {
        val vm = viewModel()
        // Drop the extracts first so the only remaining complaint is the mismatch.
        vm.uiState.value.tokens.indices
            .forEach { vm.setTokenField(it, FIELD_IGNORE) }
        vm.onPatternOverrideChange("credited")

        val state = vm.uiState.value
        assertThat(state.validationError).isEqualTo(WizardValidationError.BODY_PATTERN_NOT_MATCHING)
        assertThat(state.validationError?.field).isEqualTo(WizardField.PATTERN)
    }

    @Test
    fun `tapping Save on an invalid rule flags it instead of silently doing nothing, and fixing clears it`() =
        runTest(dispatcher) {
            val vm = viewModel()
            vm.onPatternOverrideChange("debited (Rs")
            vm.save()
            advanceUntilIdle()

            assertThat(vm.uiState.value.saveBlocked).isTrue()
            assertThat(vm.uiState.value.saved).isFalse()
            assertThat(rules.rules.value).isEmpty()

            vm.resetPatternOverride()
            assertThat(vm.uiState.value.validationError).isNull()
            assertThat(vm.uiState.value.saveBlocked).isFalse()
        }

    @Test
    fun `every validation error has a message that says which step to fix`() {
        val strings =
            listOf(File("src/main/res/values/strings_ui.xml"), File("app/src/main/res/values/strings_ui.xml"))
                .first { it.exists() }
                .readText()
        val messageKeys =
            mapOf(
                WizardValidationError.NEEDS_SAMPLE to "rule_error_needs_sample",
                WizardValidationError.NO_CONDITIONS to "rule_error_no_conditions",
                WizardValidationError.INVALID_PATTERN to "rule_error_invalid_pattern",
                WizardValidationError.INVALID_SENDER_PATTERN to "rule_error_invalid_sender_pattern",
                WizardValidationError.CATCH_ALL_WRAPPER to "rule_error_catch_all",
                WizardValidationError.DUPLICATE_FIELD to "rule_error_duplicate_field",
                WizardValidationError.CAPTURE_MISMATCH to "rule_error_capture_mismatch",
                WizardValidationError.SENDER_NOT_MATCHING to "rule_error_sender_not_matching",
                WizardValidationError.BODY_PATTERN_NOT_MATCHING to "rule_error_body_not_matching",
                WizardValidationError.MUST_CONTAIN_MISSING to "rule_error_must_contain_missing",
                WizardValidationError.MUST_NOT_CONTAIN_PRESENT to "rule_error_must_not_present",
                WizardValidationError.NO_SOURCE_MATCH to "rule_error_no_source_match",
            )
        assertThat(messageKeys.keys).containsExactlyElementsIn(WizardValidationError.entries)
        for ((error, key) in messageKeys) {
            val message = Regex("""<string name="$key">(.*?)</string>""").find(strings)?.groupValues?.get(1)
            assertThat(message).isNotNull()
            // Plain language with a fix: a step to go to (or, for the sample, what to do first).
            val actionable = message!!.contains("step") || error == WizardValidationError.NEEDS_SAMPLE
            assertThat(actionable).isTrue()
            // Errors that carry a detail quote it.
            val quotesDetail = message.contains("%1\$s")
            val hasDetail =
                error in
                    setOf(
                        WizardValidationError.INVALID_PATTERN,
                        WizardValidationError.INVALID_SENDER_PATTERN,
                        WizardValidationError.DUPLICATE_FIELD,
                        WizardValidationError.CAPTURE_MISMATCH,
                        WizardValidationError.SENDER_NOT_MATCHING,
                        WizardValidationError.MUST_CONTAIN_MISSING,
                        WizardValidationError.MUST_NOT_CONTAIN_PRESENT,
                    )
            assertThat(quotesDetail).isEqualTo(hasDetail)
        }
        // The "not saved" answer to a blocked Save tap exists too.
        assertThat(strings).contains("name=\"rule_error_not_saved_title\"")
        assertThat(strings).contains("name=\"rule_error_cannot_save\"")
    }
}
