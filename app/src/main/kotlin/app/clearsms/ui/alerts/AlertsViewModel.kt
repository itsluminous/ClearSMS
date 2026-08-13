package app.clearsms.ui.alerts

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.clearsms.data.db.ReminderEntity
import app.clearsms.data.prefs.SettingsRepository
import app.clearsms.data.repository.FinanceRepository
import app.clearsms.di.IoDispatcher
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
import java.time.LocalDate
import java.time.ZoneId
import javax.inject.Inject

data class AlertsUiState(
    val filter: AlertFilter = AlertFilter.ALL,
    val upcoming: List<ReminderEntity> = emptyList(),
    val past: List<ReminderEntity> = emptyList(),
    /** Reminders (upcoming + past) per pill, for the count badges. */
    val counts: Map<AlertFilter, Int> = emptyMap(),
    /** Pill order configured in Settings; empty means declaration order. */
    val pillOrder: List<AlertFilter> = emptyList(),
    /** Past reminders section is collapsed by default; session-only state. */
    val pastExpanded: Boolean = false,
    /** Mirrors Settings → Appearance → Show logos and contact photos. */
    val showRichAvatars: Boolean = true,
    val loaded: Boolean = false,
)

@HiltViewModel
class AlertsViewModel
    @Inject
    constructor(
        private val financeRepository: FinanceRepository,
        settingsRepository: SettingsRepository,
        private val messageLookup: MessageLookup,
        @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
    ) : ViewModel() {
        private val filter = MutableStateFlow(AlertFilter.ALL)

        /** Session-only expansion state for the "Older alerts" section (collapsed by default). */
        private val pastExpanded = MutableStateFlow(false)

        /**
         * Upcoming/past cutoff: start of TODAY, so an item due (or a package
         * expected) today still counts as upcoming rather than instantly past.
         */
        private val nowMs =
            LocalDate
                .now()
                .atStartOfDay(ZoneId.systemDefault())
                .toInstant()
                .toEpochMilli()

        val uiState: StateFlow<AlertsUiState> =
            combine(
                financeRepository.observeUpcomingReminders(nowMs),
                financeRepository.observePastReminders(nowMs),
                filter,
                pastExpanded,
                settingsRepository.showRichAvatars,
            ) { upcoming, past, currentFilter, expanded, richAvatars ->
                AlertsUiState(
                    filter = currentFilter,
                    upcoming = upcoming.filter { currentFilter.matches(it.type) },
                    past = past.filter { currentFilter.matches(it.type) },
                    counts = AlertFilter.counts(upcoming, past),
                    pastExpanded = expanded,
                    showRichAvatars = richAvatars,
                    loaded = true,
                )
            }.combine(settingsRepository.alertsPillOrder) { state, order -> state.copy(pillOrder = order) }
                .flowOn(ioDispatcher)
                .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AlertsUiState())

        // NO retention sweep: Older is the complete alert history. The
        // v0.10.5 90-day auto-purge erased alerts the user wanted to look
        // back at; rows now leave Older only via delete-forever/clear-older.

        fun setFilter(value: AlertFilter) {
            filter.value = value
        }

        /** Toggles the Older-alerts section; remembered only for the session. */
        fun togglePastExpanded() {
            pastExpanded.value = !pastExpanded.value
        }

        /** Dismisses a card: it moves to the Older section (never deleted here). */
        fun dismiss(reminderId: Long) {
            viewModelScope.launch(ioDispatcher) {
                financeRepository.dismissReminder(reminderId, System.currentTimeMillis())
            }
        }

        /** Restores (un-dismisses) a card from the Older section. */
        fun restore(reminderId: Long) {
            viewModelScope.launch(ioDispatcher) { financeRepository.restoreReminder(reminderId) }
        }

        /** Permanently deletes a card from the Older section. */
        fun deleteForever(reminderId: Long) {
            viewModelScope.launch(ioDispatcher) { financeRepository.deleteReminderForever(reminderId) }
        }

        /** Bulk "clear older": permanently deletes the whole Older archive (confirmed in the UI). */
        fun clearOlder() {
            viewModelScope.launch(ioDispatcher) { financeRepository.clearOlderReminders(nowMs) }
        }

        /** Conversation target for the SMS behind [rawSmsId]; null when it was deleted. */
        suspend fun sourceMessageFor(rawSmsId: Long): MessageRef? =
            withContext(ioDispatcher) {
                SourceMessageResolver.resolve(messageLookup.byId(rawSmsId))
            }
    }
