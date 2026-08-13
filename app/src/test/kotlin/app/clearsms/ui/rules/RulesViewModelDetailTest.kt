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
 * extracts, guards) - never an editor - and the enable/disable toggle keeps
 * its park/restore behaviour after the tap-to-edit wiring.
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
    fun `enable-disable toggle still parks and restores the rule`() =
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
            // Disabling removes the row from the engine's table and parks it.
            repository.rules.first { it.isEmpty() }
            // uiState combines the rules table with the parked-prefs flow, so
            // await the settled state (exactly one, disabled), not the
            // intermediate one where the row and the parked entry coexist.
            val parked =
                vm.uiState
                    .first { state ->
                        state.builtinRules.size == 1 && state.builtinRules.none { it.enabled }
                    }.builtinRules
                    .single()
            assertThat(parked.id).isEqualTo(bundledRule.id)
            assertThat(parked.parkedEntry).isNotNull()

            vm.setEnabled(parked, true)
            repository.rules.first { it.isNotEmpty() }
            assertThat(
                repository.rules.value
                    .single()
                    .id,
            ).isEqualTo(bundledRule.id)
            // The parked entry is removed after the rule is restored.
            uiPrefs.disabledRules.first { it.isEmpty() }

            collector.cancel()
        }
}
