package app.clearsms.ui.onboarding

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material.icons.outlined.Sync
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.clearsms.R
import app.clearsms.domain.model.ThemeMode
import app.clearsms.sms.DefaultSmsAppHelper
import app.clearsms.work.InitialSyncWorker
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.google.accompanist.permissions.rememberMultiplePermissionsState
import kotlinx.coroutines.delay

/** Onboarding flow: welcome → permissions → default SMS app → initial sync → theme. */
@OptIn(ExperimentalPermissionsApi::class)
@Composable
fun OnboardingScreen(viewModel: OnboardingViewModel = hiltViewModel()) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    Scaffold { padding ->
        AnimatedContent(
            targetState = state.step,
            label = "onboarding_step",
            modifier = Modifier.fillMaxSize().padding(padding),
        ) { step ->
            when (step) {
                OnboardingStep.WELCOME -> WelcomeStep(onNext = viewModel::next)
                OnboardingStep.PERMISSIONS -> PermissionsStep(onNext = viewModel::next)
                OnboardingStep.DEFAULT_SMS -> DefaultSmsStep(onNext = viewModel::next)
                OnboardingStep.SYNC -> SyncStep(onNext = viewModel::next)
                OnboardingStep.THEME ->
                    ThemeStep(
                        selected = state.theme,
                        onPick = viewModel::pickTheme,
                        onFinish = viewModel::finish,
                    )
            }
        }
    }
}

@Composable
private fun StepScaffold(
    icon: @Composable () -> Unit,
    title: String,
    body: String,
    content: @Composable ColumnScope.() -> Unit = {},
) {
    Column(
        modifier = Modifier.fillMaxSize().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        icon()
        Spacer(Modifier.height(24.dp))
        Text(text = title, style = MaterialTheme.typography.headlineMedium, textAlign = TextAlign.Center)
        Spacer(Modifier.height(12.dp))
        Text(
            text = body,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(32.dp))
        content()
    }
}

@Composable
private fun WelcomeStep(onNext: () -> Unit) {
    StepScaffold(
        icon = {
            Icon(
                Icons.Outlined.Lock,
                contentDescription = null,
                modifier = Modifier.size(72.dp),
                tint = MaterialTheme.colorScheme.primary,
            )
        },
        title = stringResource(R.string.onboarding_welcome_title),
        body = stringResource(R.string.onboarding_welcome_body),
    ) {
        Button(onClick = onNext, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.onboarding_get_started))
        }
    }
}

@OptIn(ExperimentalPermissionsApi::class)
@Composable
private fun PermissionsStep(onNext: () -> Unit) {
    val permissions =
        buildList {
            add(Manifest.permission.READ_SMS)
            add(Manifest.permission.RECEIVE_SMS)
            add(Manifest.permission.SEND_SMS)
            add(Manifest.permission.READ_CONTACTS)
            add(Manifest.permission.READ_PHONE_STATE)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                add(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    val permissionsState = rememberMultiplePermissionsState(permissions)

    LaunchedEffect(permissionsState.allPermissionsGranted) {
        if (permissionsState.allPermissionsGranted) onNext()
    }

    StepScaffold(
        icon = {
            Icon(
                Icons.Outlined.ChatBubbleOutline,
                contentDescription = null,
                modifier = Modifier.size(72.dp),
                tint = MaterialTheme.colorScheme.primary,
            )
        },
        title = stringResource(R.string.onboarding_permissions_title),
        body = stringResource(R.string.onboarding_permissions_body),
    ) {
        Button(
            onClick = { permissionsState.launchMultiplePermissionRequest() },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(stringResource(R.string.onboarding_grant_permissions))
        }
        TextButton(onClick = onNext) {
            Text(stringResource(R.string.onboarding_skip))
        }
    }
}

@Composable
private fun DefaultSmsStep(onNext: () -> Unit) {
    val context = LocalContext.current
    val launcher =
        rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            if (DefaultSmsAppHelper.isDefaultSmsApp(context)) onNext()
        }

    LaunchedEffect(Unit) {
        if (DefaultSmsAppHelper.isDefaultSmsApp(context)) onNext()
    }

    StepScaffold(
        icon = {
            Icon(
                Icons.Outlined.ChatBubbleOutline,
                contentDescription = null,
                modifier = Modifier.size(72.dp),
                tint = MaterialTheme.colorScheme.primary,
            )
        },
        title = stringResource(R.string.onboarding_default_sms_title),
        body = stringResource(R.string.onboarding_default_sms_body),
    ) {
        Button(
            onClick = { launcher.launch(DefaultSmsAppHelper.createRequestIntent(context)) },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(stringResource(R.string.onboarding_set_default))
        }
        TextButton(onClick = onNext) {
            Text(stringResource(R.string.onboarding_skip))
        }
    }
}

@Composable
private fun SyncStep(onNext: () -> Unit) {
    val context = LocalContext.current

    LaunchedEffect(Unit) {
        InitialSyncWorker.enqueue(context)
        // The import runs in the background; give it a moment before moving on.
        delay(2_500)
        onNext()
    }

    StepScaffold(
        icon = {
            Icon(
                Icons.Outlined.Sync,
                contentDescription = null,
                modifier = Modifier.size(72.dp),
                tint = MaterialTheme.colorScheme.primary,
            )
        },
        title = stringResource(R.string.onboarding_sync_title),
        body = stringResource(R.string.onboarding_sync_body),
    ) {
        CircularProgressIndicator()
    }
}

@Composable
private fun ThemeStep(
    selected: ThemeMode,
    onPick: (ThemeMode) -> Unit,
    onFinish: () -> Unit,
) {
    StepScaffold(
        icon = {
            Icon(
                Icons.Outlined.Palette,
                contentDescription = null,
                modifier = Modifier.size(72.dp),
                tint = MaterialTheme.colorScheme.primary,
            )
        },
        title = stringResource(R.string.onboarding_theme_title),
        body = stringResource(R.string.onboarding_theme_body),
    ) {
        ThemeMode.entries.forEach { mode ->
            Row(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .selectable(
                            selected = selected == mode,
                            onClick = { onPick(mode) },
                            role = Role.RadioButton,
                        ).padding(vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RadioButton(selected = selected == mode, onClick = null)
                Spacer(Modifier.padding(horizontal = 6.dp))
                Text(
                    text =
                        when (mode) {
                            ThemeMode.SYSTEM -> stringResource(R.string.theme_system)
                            ThemeMode.LIGHT -> stringResource(R.string.theme_light)
                            ThemeMode.DARK -> stringResource(R.string.theme_dark)
                        },
                )
            }
        }
        Spacer(Modifier.height(16.dp))
        Button(onClick = onFinish, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.onboarding_done))
        }
    }
}
