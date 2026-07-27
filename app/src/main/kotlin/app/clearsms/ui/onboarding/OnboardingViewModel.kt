package app.clearsms.ui.onboarding

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.clearsms.data.prefs.SettingsRepository
import app.clearsms.di.IoDispatcher
import app.clearsms.domain.model.ThemeMode
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
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
)

@HiltViewModel
class OnboardingViewModel
    @Inject
    constructor(
        private val settings: SettingsRepository,
        @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
    ) : ViewModel() {
        private val state = MutableStateFlow(OnboardingUiState())
        val uiState: StateFlow<OnboardingUiState> = state.asStateFlow()

        fun next() {
            val steps = OnboardingStep.entries
            val index = steps.indexOf(state.value.step)
            if (index < steps.lastIndex) {
                state.value = state.value.copy(step = steps[index + 1])
            }
        }

        fun pickTheme(theme: ThemeMode) {
            state.value = state.value.copy(theme = theme)
            viewModelScope.launch(ioDispatcher) { settings.setTheme(theme) }
        }

        fun finish() {
            viewModelScope.launch(ioDispatcher) { settings.setOnboardingComplete(true) }
        }
    }
