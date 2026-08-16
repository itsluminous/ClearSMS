package app.clearsms.ui.navigation

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import app.clearsms.R
import app.clearsms.ui.components.TooltipIconButton

/**
 * The ONE search + settings action pair shared by every top-level tab's app
 * bar (Inbox, Finance, Alerts), so the three screens cannot drift apart:
 * search always opens the same message search, settings always opens the same
 * Settings screen, with identical icons and content descriptions.
 *
 * [TooltipIconButton] already enforces the 48dp minimum touch target and
 * reveals the label on long-press.
 */
@Composable
fun SearchSettingsActions(
    onSearch: () -> Unit,
    onSettings: () -> Unit,
) {
    TooltipIconButton(
        label = stringResource(R.string.action_search),
        onClick = onSearch,
        icon = Icons.Outlined.Search,
    )
    TooltipIconButton(
        label = stringResource(R.string.action_settings),
        onClick = onSettings,
        icon = Icons.Outlined.Settings,
    )
}
