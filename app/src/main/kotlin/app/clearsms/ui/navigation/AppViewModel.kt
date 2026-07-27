package app.clearsms.ui.navigation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.clearsms.data.prefs.SettingsRepository
import app.clearsms.domain.model.ThemeMode
import app.clearsms.ui.common.UiPrefs
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

/** App-level chrome state: theme and the onboarding gate. */
data class AppUiState(
    /** Null until the settings DataStore has been read. */
    val onboardingComplete: Boolean? = null,
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    val dynamicColor: Boolean = true,
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
            ) { onboarded, theme, dynamic ->
                AppUiState(onboardingComplete = onboarded, themeMode = theme, dynamicColor = dynamic)
            }.stateIn(viewModelScope, SharingStarted.Eagerly, AppUiState())
    }
