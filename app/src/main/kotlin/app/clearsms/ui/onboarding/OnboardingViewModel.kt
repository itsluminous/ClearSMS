package app.clearsms.ui.onboarding

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.work.WorkManager
import app.clearsms.data.prefs.SettingsRepository
import app.clearsms.di.IoDispatcher
import app.clearsms.domain.model.ThemeMode
import app.clearsms.work.InitialSyncWorker
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/** Ordered onboarding steps. */
enum class OnboardingStep {
    WELCOME,
    PERMISSIONS,
    DEFAULT_SMS,
    SYNC,
    THEME,
}

data class OnboardingUiState(
    val step: OnboardingStep = OnboardingStep.WELCOME,
    val theme: ThemeMode = ThemeMode.SYSTEM,
    /** Messages processed / total, from the sync worker's progress. */
    val syncImported: Int = 0,
    val syncTotal: Int = 0,
)

@HiltViewModel
class OnboardingViewModel
    @Inject
    constructor(
        private val settings: SettingsRepository,
        private val workManager: WorkManager,
        @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
    ) : ViewModel() {
        private val state = MutableStateFlow(OnboardingUiState())
        val uiState: StateFlow<OnboardingUiState> = state.asStateFlow()

        init {
            // The ViewModel only OBSERVES the import; the worker owns it. When
            // the unique work reaches a terminal state while the sync step is
            // showing, onboarding advances automatically.
            viewModelScope.launch {
                workManager
                    .getWorkInfosForUniqueWorkFlow(InitialSyncWorker.WORK_NAME)
                    .collect { infos ->
                        val info = infos.firstOrNull() ?: return@collect
                        state.update {
                            it.copy(
                                syncImported = info.progress.getInt(InitialSyncWorker.PROGRESS_IMPORTED, 0),
                                syncTotal = info.progress.getInt(InitialSyncWorker.PROGRESS_TOTAL, 0),
                            )
                        }
                        if (info.state.isFinished && state.value.step == OnboardingStep.SYNC) {
                            next()
                        }
                    }
            }
        }

        fun next() {
            val steps = OnboardingStep.entries
            val index = steps.indexOf(state.value.step)
            if (index < steps.lastIndex) {
                val newStep = steps[index + 1]
                state.update { it.copy(step = newStep) }
                if (newStep == OnboardingStep.SYNC) {
                    // KEEP policy + durable checkpoint: never restarts a
                    // running import, and re-enqueueing after a completed one
                    // only picks up rows not imported yet.
                    InitialSyncWorker.enqueue(workManager)
                }
            }
        }

        fun pickTheme(theme: ThemeMode) {
            state.update { it.copy(theme = theme) }
            viewModelScope.launch(ioDispatcher) { settings.setTheme(theme) }
        }

        fun finish() {
            viewModelScope.launch(ioDispatcher) { settings.setOnboardingComplete(true) }
        }
    }
