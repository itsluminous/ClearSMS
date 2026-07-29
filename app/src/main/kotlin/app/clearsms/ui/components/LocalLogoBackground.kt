package app.clearsms.ui.components

import androidx.compose.runtime.staticCompositionLocalOf
import app.clearsms.domain.model.LogoBackground

/**
 * The user's chosen backing plate for bundled sender logos, provided once at
 * the app root (see `ClearSmsApp`) rather than threaded through every
 * [SenderAvatar] call site — avatars render in the inbox, conversations,
 * search, Finance and Alerts, and none of those screens otherwise care about
 * this preference.
 */
val LocalLogoBackground = staticCompositionLocalOf { LogoBackground.WHITE }
