package app.clearsms.ui.alerts

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.clearsms.data.db.ReminderDao
import app.clearsms.data.db.ReminderEntity
import app.clearsms.data.repository.FinanceRepository
import app.clearsms.di.IoDispatcher
import app.clearsms.domain.model.ReminderType
import app.clearsms.ui.finance.MessageLookup
import app.clearsms.ui.finance.MessageRef
import app.clearsms.ui.finance.SourceMessageResolver
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

/** Reminder filter chips: all, credit cards, EMI, everything else. */
enum class AlertFilter {
    ALL,
    CREDIT_CARDS,
    EMI,
    OTHERS,
}

data class AlertsUiState(
    val filter: AlertFilter = AlertFilter.ALL,
    val upcoming: List<ReminderEntity> = emptyList(),
    val past: List<ReminderEntity> = emptyList(),
    /** Past reminders section is collapsed by default; session-only state. */
    val pastExpanded: Boolean = false,
    val loaded: Boolean = false,
)

@HiltViewModel
class AlertsViewModel
    @Inject
    constructor(
        financeRepository: FinanceRepository,
        private val reminderDao: ReminderDao,
        private val messageLookup: MessageLookup,
        @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
    ) : ViewModel() {
        private val filter = MutableStateFlow(AlertFilter.ALL)

        /** Session-only expansion state for the "Past reminders" section (collapsed by default). */
        private val pastExpanded = MutableStateFlow(false)
        private val nowMs = System.currentTimeMillis()

        val uiState: StateFlow<AlertsUiState> =
            combine(
                financeRepository.observeUpcomingReminders(nowMs),
                financeRepository.observePastReminders(nowMs),
                filter,
                pastExpanded,
            ) { upcoming, past, currentFilter, expanded ->
                AlertsUiState(
                    filter = currentFilter,
                    upcoming = upcoming.filter { matches(it, currentFilter) },
                    past = past.filter { matches(it, currentFilter) },
                    pastExpanded = expanded,
                    loaded = true,
                )
            }.flowOn(ioDispatcher)
                .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AlertsUiState())

        fun setFilter(value: AlertFilter) {
            filter.value = value
        }

        /** Toggles the past-reminders section; remembered only for the session. */
        fun togglePastExpanded() {
            pastExpanded.value = !pastExpanded.value
        }

        /** Dismisses (removes) a reminder card. */
        fun dismiss(reminderId: Long) {
            viewModelScope.launch(ioDispatcher) { reminderDao.deleteById(reminderId) }
        }

        /** Conversation target for the SMS behind [rawSmsId]; null when it was deleted. */
        suspend fun sourceMessageFor(rawSmsId: Long): MessageRef? =
            withContext(ioDispatcher) {
                SourceMessageResolver.resolve(messageLookup.byId(rawSmsId))
            }

        private fun matches(
            reminder: ReminderEntity,
            filter: AlertFilter,
        ): Boolean =
            when (filter) {
                AlertFilter.ALL -> true
                AlertFilter.CREDIT_CARDS -> reminder.type == ReminderType.CREDIT_CARD
                AlertFilter.EMI -> reminder.type == ReminderType.EMI
                AlertFilter.OTHERS -> reminder.type != ReminderType.CREDIT_CARD && reminder.type != ReminderType.EMI
            }
    }
