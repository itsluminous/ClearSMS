package app.clearsms.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.clearsms.R

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun StaticTextScreen(
    title: String,
    onBack: () -> Unit,
    paragraphs: List<Pair<String, String>>,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(title) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Outlined.ArrowBack,
                            contentDescription = stringResource(R.string.action_back),
                        )
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier =
                Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp),
        ) {
            paragraphs.forEach { (heading, body) ->
                Text(
                    text = heading,
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(top = 16.dp, bottom = 4.dp),
                )
                Text(
                    text = body,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/** Privacy policy: the on-device processing statement. */
@Composable
fun PrivacyPolicyScreen(onBack: () -> Unit) {
    StaticTextScreen(
        title = stringResource(R.string.settings_privacy_policy),
        onBack = onBack,
        paragraphs =
            listOf(
                stringResource(R.string.privacy_ondevice_title) to stringResource(R.string.privacy_ondevice_body),
                stringResource(R.string.privacy_no_network_title) to stringResource(R.string.privacy_no_network_body),
                stringResource(R.string.privacy_backups_title) to stringResource(R.string.privacy_backups_body),
                stringResource(R.string.privacy_rules_title) to stringResource(R.string.privacy_rules_body),
            ),
    )
}

/** Static open-source license attributions. */
@Composable
fun LicensesScreen(onBack: () -> Unit) {
    StaticTextScreen(
        title = stringResource(R.string.settings_licenses),
        onBack = onBack,
        paragraphs =
            listOf(
                "Clear SMS" to stringResource(R.string.licenses_app),
                "AndroidX / Jetpack Compose" to stringResource(R.string.licenses_apache2),
                "Kotlin & kotlinx libraries" to stringResource(R.string.licenses_apache2),
                "Material Components for Android" to stringResource(R.string.licenses_apache2),
                "Dagger / Hilt" to stringResource(R.string.licenses_apache2),
                "Accompanist" to stringResource(R.string.licenses_apache2),
                "Coil" to stringResource(R.string.licenses_apache2),
            ),
    )
}

/** Explains why each permission is needed. */
@Composable
fun PermissionsInfoScreen(onBack: () -> Unit) {
    StaticTextScreen(
        title = stringResource(R.string.settings_permissions),
        onBack = onBack,
        paragraphs =
            listOf(
                "READ_SMS / RECEIVE_SMS" to stringResource(R.string.perm_sms_body),
                "SEND_SMS" to stringResource(R.string.perm_send_body),
                "READ_CONTACTS" to stringResource(R.string.perm_contacts_body),
                "READ_PHONE_STATE" to stringResource(R.string.perm_phone_body),
                "POST_NOTIFICATIONS" to stringResource(R.string.perm_notifications_body),
                "RECEIVE_BOOT_COMPLETED" to stringResource(R.string.perm_boot_body),
            ),
    )
}
