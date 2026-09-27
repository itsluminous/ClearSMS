package app.clearsms.ui.diagnostics

import android.content.ActivityNotFoundException
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.clearsms.R
import app.clearsms.diagnostics.ReportWindow
import kotlinx.coroutines.launch

/**
 * Settings → About → Share diagnostic logs.
 *
 * The screen IS the preview: the text shown here is byte-for-byte what the
 * share sheet sends (zipped), so a user who cannot judge a log line can at
 * least see there is no message text, number or name in it. The window
 * chips and the mask checkbox rebuild the preview immediately; Share only
 * fires from the bottom button; Back / Cancel leaves nothing behind.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun DiagnosticsScreen(
    onBack: () -> Unit,
    viewModel: DiagnosticsViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.settings_share_logs)) },
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
        bottomBar = {
            Row(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                OutlinedButton(onClick = onBack, modifier = Modifier.weight(1f)) {
                    Text(stringResource(R.string.action_cancel))
                }
                Button(
                    enabled = !state.loading,
                    onClick = {
                        scope.launch {
                            val intent = viewModel.buildShareIntent() ?: return@launch
                            try {
                                context.startActivity(intent)
                            } catch (_: ActivityNotFoundException) {
                                // No app can take a zip: the failure text below covers it.
                            }
                        }
                    },
                    modifier = Modifier.weight(1f),
                ) {
                    Text(stringResource(R.string.diagnostics_share_button))
                }
            }
        },
    ) { padding ->
        Column(
            modifier =
                Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = stringResource(R.string.diagnostics_intro),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(stringResource(R.string.diagnostics_window), style = MaterialTheme.typography.labelLarge)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ReportWindow.entries.forEach { window ->
                    FilterChip(
                        selected = state.window == window,
                        onClick = { viewModel.setWindow(window) },
                        label = { Text(windowLabel(window)) },
                    )
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(checked = state.maskSenders, onCheckedChange = viewModel::setMaskSenders)
                Column(modifier = Modifier.padding(start = 4.dp)) {
                    Text(stringResource(R.string.diagnostics_mask_senders), style = MaterialTheme.typography.bodyLarge)
                    Text(
                        text =
                            stringResource(
                                if (state.maskSenders) {
                                    R.string.diagnostics_mask_senders_on
                                } else {
                                    R.string.diagnostics_mask_senders_off
                                },
                            ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Text(
                text =
                    if (state.loading) {
                        stringResource(R.string.diagnostics_loading)
                    } else {
                        stringResource(R.string.diagnostics_preview_heading, state.zipBytes / 1024 + 1)
                    },
                style = MaterialTheme.typography.labelLarge,
            )
            if (state.shareFailed) {
                Text(
                    text = stringResource(R.string.diagnostics_share_failed),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            // The exact shared text, scrollable both ways so long lines are
            // not wrapped into something that reads differently.
            Column(
                modifier =
                    Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant)
                        .verticalScroll(rememberScrollState())
                        .horizontalScroll(rememberScrollState())
                        .padding(8.dp),
            ) {
                Text(
                    text = state.preview,
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    softWrap = false,
                )
            }
        }
    }
}

@Composable
private fun windowLabel(window: ReportWindow): String =
    when (window) {
        ReportWindow.LAST_5_MINUTES -> stringResource(R.string.diagnostics_window_minutes, 5)
        ReportWindow.LAST_15_MINUTES -> stringResource(R.string.diagnostics_window_minutes, 15)
        ReportWindow.LAST_HOUR -> stringResource(R.string.diagnostics_window_hour)
        ReportWindow.EVERYTHING -> stringResource(R.string.diagnostics_window_everything)
    }
