package app.clearsms.ui.navigation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.clearsms.data.prefs.SettingsRepository
import app.clearsms.domain.model.EnabledSections
import app.clearsms.domain.model.LogoBackground
import app.clearsms.domain.model.MessageTextSize
import app.clearsms.domain.model.StartDestination
import app.clearsms.domain.model.ThemeMode
import app.clearsms.domain.model.TimeFormat
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
    /** Which top-level sections (tabs) exist for this user; never all-off. */
    val sections: EnabledSections = EnabledSections(),
    val logoBackground: LogoBackground = LogoBackground.WHITE,
    /** 12/24-hour clock for every time the app writes (#158). */
    val timeFormat: TimeFormat = TimeFormat.SYSTEM,
    /** In-app multiplier for bubble text, on top of the system font scale (#157). */
    val messageTextSize: MessageTextSize = MessageTextSize.DEFAULT,
)

/**
 * The two Appearance display preferences read together: `combine` is typed
 * up to five flows and the main stage already uses all five, so these join
 * as one typed value in a further stage, like `enabledSections`.
 */
private data class DisplayPrefs(
    val timeFormat: TimeFormat,
    val messageTextSize: MessageTextSize,
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
                settings.logoBackground,
            ) { onboarded, theme, dynamic, destination, logoBackground ->
                AppUiState(
                    onboardingComplete = onboarded,
                    themeMode = theme,
                    dynamicColor = dynamic,
                    defaultDestination = destination,
                    logoBackground = logoBackground,
                )
            }.combine(settings.enabledSections) { state, sections ->
                // Same DataStore read as the rest, so the first emission that
                // clears the onboarding gate already carries the real flags -
                // cold start can never flash a disabled tab.
                state.copy(sections = sections)
            }.combine(combine(settings.timeFormat, settings.messageTextSize, ::DisplayPrefs)) { state, display ->
                state.copy(timeFormat = display.timeFormat, messageTextSize = display.messageTextSize)
            }.stateIn(viewModelScope, SharingStarted.Eagerly, AppUiState())
    }
