package app.clearsms.ui.rules

import app.clearsms.data.rules.RuleSources
import app.clearsms.data.rules.toDefinition
import app.clearsms.domain.rules.SenderRule
import app.clearsms.testing.FakeMessageRepository
import app.clearsms.testing.FakeRuleRepository
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.Test

/**
 * Saving the one-step sender rule: stored as a user rule, and that sender's
 * existing messages re-sorted on the spot (never the full re-sort dialog) -
 * the reporter's complaint was that a saved rule visibly did nothing.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SenderRuleViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val json = Json { ignoreUnknownKeys = true }
    private val rules = FakeRuleRepository()
    private val messages =
        object : FakeMessageRepository() {
            override suspend fun recategorizeSenderCore(senderCore: String): Int {
                recategorizedSenderCores += senderCore
                return 7
            }
        }

    private fun viewModel() = SenderRuleViewModel(rules, messages, dispatcher)

    @Test
    fun `save stores a sender-only user rule for each category and re-sorts that sender immediately`() =
        runTest(dispatcher) {
            for ((index, category) in SenderRule.CATEGORIES.withIndex()) {
                val vm = viewModel()
                vm.save("VM-HDFCBK-S", category)
                advanceUntilIdle()

                val stored = rules.rules.value.map { it.toDefinition(json)!! }
                assertThat(stored).hasSize(index + 1)
                val rule = stored.last()
                assertThat(rule.action.category).isEqualTo(category)
                assertThat(rule.match.senderPattern).isEqualTo("(?i)HDFCBK")
                assertThat(SenderRule.isSenderOnly(rule)).isTrue()
                assertThat(
                    rules.rules.value
                        .last()
                        .source,
                ).isEqualTo(RuleSources.USER)
                // Immediate path: the sender core was re-sorted, no "run the full re-sort" outcome.
                assertThat(messages.recategorizedSenderCores.last()).isEqualTo("HDFCBK")
                assertThat(vm.saved.value).isEqualTo(SenderRuleSaved("HDFCBK", category, messages = 7))
            }
        }

    @Test
    fun `a sender with regex-special characters is escaped and still re-sorted immediately`() =
        runTest(dispatcher) {
            val vm = viewModel()
            vm.save("AX-AB.CD", "promotional")
            advanceUntilIdle()

            val rule =
                rules.rules.value
                    .single()
                    .toDefinition(json)!!
            assertThat(rule.match.senderPattern).isEqualTo("(?i)AB\\.CD")
            assertThat(messages.recategorizedSenderCores).containsExactly("AB.CD")
            assertThat(vm.saved.value?.senderCore).isEqualTo("AB.CD")
        }

    @Test
    fun `a phone-number sender is re-sorted by its normalised digits`() =
        runTest(dispatcher) {
            viewModel().save("+91 98765 43210", "personal")
            advanceUntilIdle()

            assertThat(messages.recategorizedSenderCores).containsExactly("9876543210")
        }

    @Test
    fun `consuming the saved outcome clears it so a snackbar shows once`() =
        runTest(dispatcher) {
            val vm = viewModel()
            vm.save("VM-HDFCBK", "otp")
            advanceUntilIdle()
            assertThat(vm.saved.value).isNotNull()

            vm.consumeSaved()

            assertThat(vm.saved.value).isNull()
        }

    @Test
    fun `a blank sender or unknown category saves nothing`() =
        runTest(dispatcher) {
            val vm = viewModel()
            vm.save("", "promotional")
            vm.save("VM-HDFCBK", "spam")
            advanceUntilIdle()

            assertThat(rules.rules.value).isEmpty()
            assertThat(messages.recategorizedSenderCores).isEmpty()
            assertThat(vm.saved.value).isNull()
        }
}
