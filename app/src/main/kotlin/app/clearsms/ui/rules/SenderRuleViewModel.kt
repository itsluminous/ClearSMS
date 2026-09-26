package app.clearsms.ui.rules

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.clearsms.data.repository.MessageRepository
import app.clearsms.data.repository.RuleRepository
import app.clearsms.di.IoDispatcher
import app.clearsms.domain.rules.RuleApplyScope
import app.clearsms.domain.rules.RuleScopeResolver
import app.clearsms.domain.rules.SenderRule
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/** What saving a one-tap sender rule did, for the confirmation snackbar. */
data class SenderRuleSaved(
    /** The sender core the rule is about, as shown to the user. */
    val senderCore: String,
    /** Category key the sender is now sorted as. */
    val category: String,
    /** Existing messages re-sorted on the spot. */
    val messages: Int,
)

/**
 * Saves "always sort this sender as <category>" - the dead-simple rule for
 * users who found the wizard confusing (issue #38). The rule is an ordinary
 * sender-bound user rule ([SenderRule.definition]), and because its pattern
 * is a literal the app composed, [RuleScopeResolver] always resolves it to
 * the sender scope: that sender's existing messages are re-sorted at once,
 * so the user sees the rule work instead of wondering whether it saved.
 */
@HiltViewModel
class SenderRuleViewModel
    @Inject
    constructor(
        private val ruleRepository: RuleRepository,
        private val messageRepository: MessageRepository,
        @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
    ) : ViewModel() {
        private val state = MutableStateFlow<SenderRuleSaved?>(null)

        /** Set once a save finished; cleared by [consumeSaved] after it is shown. */
        val saved: StateFlow<SenderRuleSaved?> = state.asStateFlow()

        fun save(
            sender: String,
            category: String,
        ) {
            if (!SenderRule.canBuild(sender) || category !in SenderRule.CATEGORIES) return
            val definition = SenderRule.definition(sender, category)
            viewModelScope.launch(ioDispatcher) {
                ruleRepository.addUserRule(definition)
                val scope =
                    RuleScopeResolver.resolve(
                        senderPattern = definition.match.senderPattern.orEmpty(),
                        sourceSender = sender,
                        boundToSender = true,
                        senderPatternEdited = false,
                    )
                val count =
                    when (scope) {
                        is RuleApplyScope.Sender -> messageRepository.recategorizeSenderCore(scope.senderCore)
                        // Unreachable by construction (the pattern is a literal);
                        // kept exhaustive so a resolver change cannot silently
                        // leave the sender's messages unsorted.
                        RuleApplyScope.Everything -> 0
                    }
                state.value = SenderRuleSaved(SenderRule.senderCore(sender), category, count)
            }
        }

        fun consumeSaved() {
            state.value = null
        }
    }
