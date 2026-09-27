package app.clearsms.ui.rules

import app.clearsms.data.rules.RuleAction
import app.clearsms.data.rules.RuleDefinition
import app.clearsms.data.rules.RuleMatch
import app.clearsms.data.rules.RuleSources
import app.clearsms.data.rules.toEntity
import app.clearsms.testing.FakeRuleRepository
import app.clearsms.testing.InMemoryPreferencesDataStore
import app.clearsms.ui.common.UiPrefs
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Tapping a BUNDLED rule opens a read-only detail (pattern, priority,
 * extracts, guards) - never an editor; the enable/disable toggle flips the
 * row's flag in place; and rules an older version parked in preferences are
 * folded back into the table once (issue #43).
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class RulesViewModelDetailTest {
    private val dispatcher = StandardTestDispatcher()
    private val json = Json { ignoreUnknownKeys = true }

    private val bundledRule =
        RuleDefinition(
            id = "hdfc-debit",
            name = "HDFC debit",
            priority = 500,
            match =
                RuleMatch(
                    senderPattern = ".*HDFCBK.*",
                    bodyPattern = "debited\\s+Rs\\.?\\s*([\\d,]+)",
                    bodyMustContain = listOf("debited"),
                    bodyMustNotContain = listOf("reversed"),
                    guardsNone = listOf("otp_mention"),
                ),
            action = RuleAction(category = "important", subCategory = "transaction", extract = mapOf("amount" to "$1")),
        )

    private lateinit var repository: FakeRuleRepository
    private lateinit var uiPrefs: UiPrefs

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        repository = FakeRuleRepository(initial = listOf(bundledRule.toEntity(json, RuleSources.BUILTIN)))
        // In-memory store: park/restore SEMANTICS need no real file DataStore,
        // and the file-backed one both runs outside the test scheduler and
        // races new collectors against concurrent writes on datastore 1.1.x
        // (b/431787506) - the proven cause of the CI-only 60s hang here.
        uiPrefs = UiPrefs(InMemoryPreferencesDataStore())
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun viewModel() =
        RulesViewModel(
            ruleRepository = repository,
            uiPrefs = uiPrefs,
            json = json,
            ioDispatcher = dispatcher,
        )

    @Test
    fun `showDetail exposes the bundled rule read-only fields`() =
        runTest(dispatcher) {
            val vm = viewModel()
            advanceUntilIdle()

            vm.showDetail(bundledRule.id)
            advanceUntilIdle()

            val detail = requireNotNull(vm.ruleDetail.value)
            assertThat(detail.id).isEqualTo("hdfc-debit")
            assertThat(detail.name).isEqualTo("HDFC debit")
            assertThat(detail.priority).isEqualTo(500)
            assertThat(detail.category).isEqualTo("important")
            assertThat(detail.bodyPattern).isEqualTo(bundledRule.match.bodyPattern)
            assertThat(detail.senderPattern).isEqualTo(".*HDFCBK.*")
            assertThat(detail.mustContain).containsExactly("debited")
            assertThat(detail.mustNotContain).containsExactly("reversed")
            assertThat(detail.guardsNone).containsExactly("otp_mention")
            assertThat(detail.extract).containsEntry("amount", "$1")
            assertThat(detail.isUserDefined).isFalse()

            vm.dismissDetail()
            assertThat(vm.ruleDetail.value).isNull()
        }

    @Test
    fun `enable-disable toggle flips the row flag in place - no parking, no second store`() =
        runTest(dispatcher) {
            val vm = viewModel()
            // uiState is WhileSubscribed: keep a collector alive during the test.
            val collector = launch { vm.uiState.collect {} }
            val shown =
                vm.uiState
                    .first { it.loaded }
                    .builtinRules
                    .single()
            assertThat(shown.enabled).isTrue()

            vm.setEnabled(shown, false)
            advanceUntilIdle()
            // The row stays (the engine filters on the flag); nothing is parked,
            // so there is no window in which the rule exists in two stores.
            assertThat(
                repository.rules.value
                    .single()
                    .enabled,
            ).isFalse()
            assertThat(
                repository.rules.value
                    .single()
                    .source,
            ).isEqualTo(RuleSources.BUILTIN)
            assertThat(uiPrefs.disabledRules.first()).isEmpty()
            val disabled =
                vm.uiState.value.builtinRules
                    .single()
            assertThat(disabled.enabled).isFalse()
            assertThat(disabled.parkedEntry).isNull()

            vm.setEnabled(disabled, true)
            advanceUntilIdle()
            val row = repository.rules.value.single()
            assertThat(row.enabled).isTrue()
            // Re-enabling used to re-insert the builtin as a USER rule.
            assertThat(row.source).isEqualTo(RuleSources.BUILTIN)
            assertThat(row.isUserDefined).isFalse()
            assertThat(
                vm.uiState.value.builtinRules
                    .single()
                    .enabled,
            ).isTrue()

            collector.cancel()
        }

    private fun parkedEntry(
        definition: RuleDefinition,
        source: String,
    ) = "$source|" + json.encodeToString(RuleDefinition.serializer(), definition)

    @Test
    fun `legacy parked rules are folded into the table as disabled rows with their source`() =
        runTest(dispatcher) {
            val mine = RuleDefinition(id = "user_abcd1234", name = "Mine", action = RuleAction(category = "personal"))
            val theirs = RuleDefinition(id = "sbi-otp", name = "SBI OTP", action = RuleAction(category = "otp"))
            uiPrefs.addDisabledRule(parkedEntry(mine, "user"))
            uiPrefs.addDisabledRule(parkedEntry(theirs, "builtin"))

            val vm = viewModel()
            val collector = launch { vm.uiState.collect {} }
            advanceUntilIdle()

            val rows = repository.rules.value.associateBy { it.id }
            assertThat(rows.keys).containsExactly("hdfc-debit", "user_abcd1234", "sbi-otp")
            assertThat(rows.getValue("user_abcd1234").enabled).isFalse()
            assertThat(rows.getValue("user_abcd1234").source).isEqualTo(RuleSources.USER)
            assertThat(rows.getValue("sbi-otp").enabled).isFalse()
            assertThat(rows.getValue("sbi-otp").source).isEqualTo(RuleSources.BUILTIN)
            assertThat(uiPrefs.disabledRules.first()).isEmpty()
            val state = vm.uiState.value
            assertThat(state.userRules.map { it.id }).containsExactly("user_abcd1234")
            assertThat(state.builtinRules.map { it.id }).containsExactly("hdfc-debit", "sbi-otp")

            collector.cancel()
        }

    @Test
    fun `a stale parked copy of a rule the table already holds is dropped, the row untouched`() =
        runTest(dispatcher) {
            // The persisted state behind issue #43: the same id in the table
            // AND the parked set. The page must open, show it once, and heal.
            uiPrefs.addDisabledRule(parkedEntry(bundledRule, "builtin"))

            val vm = viewModel()
            val collector = launch { vm.uiState.collect {} }
            val state = vm.uiState.first { it.loaded }
            assertThat(state.builtinRules.map { it.id }).containsExactly("hdfc-debit")
            advanceUntilIdle()

            val row = repository.rules.value.single()
            assertThat(row.enabled).isTrue()
            assertThat(row.name).isEqualTo("HDFC debit")
            assertThat(uiPrefs.disabledRules.first()).isEmpty()

            collector.cancel()
        }

    @Test
    fun `deleting a rule also removes any legacy parked copy of it`() =
        runTest(dispatcher) {
            val mine = RuleDefinition(id = "user_abcd1234", name = "Mine", action = RuleAction(category = "personal"))
            repository.addUserRule(mine)
            val entry = parkedEntry(mine, "user")
            uiPrefs.addDisabledRule(entry)
            val vm = viewModel()

            vm.deleteUserRule("user_abcd1234")
            advanceUntilIdle()

            assertThat(repository.rules.value.map { it.id }).containsExactly("hdfc-debit")
            assertThat(uiPrefs.disabledRules.first()).isEmpty()
        }
}
