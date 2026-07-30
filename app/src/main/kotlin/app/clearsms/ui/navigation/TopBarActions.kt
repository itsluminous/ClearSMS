package app.clearsms.ui.navigation

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import app.clearsms.R

/**
 * The ONE search + settings action pair shared by every top-level tab's app
 * bar (Inbox, Finance, Alerts), so the three screens cannot drift apart:
 * search always opens the same message search, settings always opens the same
 * Settings screen, with identical icons and content descriptions.
 *
 * [IconButton] already enforces the 48dp minimum touch target.
 */
@Composable
fun SearchSettingsActions(
    onSearch: () -> Unit,
    onSettings: () -> Unit,
) {
    IconButton(onClick = onSearch) {
        Icon(Icons.Outlined.Search, contentDescription = stringResource(R.string.action_search))
    }
    IconButton(onClick = onSettings) {
        Icon(Icons.Outlined.Settings, contentDescription = stringResource(R.string.action_settings))
    }
}
