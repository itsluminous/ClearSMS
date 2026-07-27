package app.clearsms.ui.navigation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.clearsms.data.prefs.SettingsRepository
import app.clearsms.domain.model.StartDestination
import app.clearsms.domain.model.ThemeMode
import app.clearsms.ui.common.UiPrefs
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

/** App-level chrome state: theme, onboarding gate and startup destination. */
data class AppUiState(
    /** Null until the settings DataStore has been read. */
    val onboardingComplete: Boolean? = null,
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    val dynamicColor: Boolean = true,
    val defaultDestination: StartDestination = StartDestination.INBOX,
)

@HiltViewModel
class AppViewModel
    @Inject
    constructor(
        settings: SettingsRepository,
        uiPrefs: UiPrefs,
    ) : ViewModel() {
        val uiState: StateFlow<AppUiState> =
            combine(
                settings.onboardingComplete,
                settings.theme,
                uiPrefs.dynamicColor,
                settings.defaultDestination,
            ) { onboarded, theme, dynamic, destination ->
                AppUiState(
                    onboardingComplete = onboarded,
                    themeMode = theme,
                    dynamicColor = dynamic,
                    defaultDestination = destination,
                )
            }.stateIn(viewModelScope, SharingStarted.Eagerly, AppUiState())
    }
