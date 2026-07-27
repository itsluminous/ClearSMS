package app.clearsms.ui.rules

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.Rule
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.FileDownload
import androidx.compose.material.icons.outlined.FileUpload
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.clearsms.R
import app.clearsms.ui.components.EmptyState

/** Rule management: builtin/user groups, enable toggles, SAF import/export, share. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RulesScreen(
    onBack: () -> Unit,
    onCreateRule: () -> Unit,
    viewModel: RulesViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val snackbarHostState = remember { SnackbarHostState() }
    var pendingExport by remember { mutableStateOf<String?>(null) }
    val importFailed = stringResource(R.string.rules_import_failed)
    val importSucceeded = stringResource(R.string.rules_import_success)
    val shareSubject = stringResource(R.string.rules_share_subject)

    val exportLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
            val payload = pendingExport
            pendingExport = null
            if (uri != null && payload != null) {
                context.contentResolver.openOutputStream(uri)?.use { it.write(payload.toByteArray()) }
            }
        }
    val importLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) {
                val text =
                    context.contentResolver
                        .openInputStream(uri)
                        ?.use { it.readBytes().decodeToString() }
                if (text != null) viewModel.import(text)
            }
        }

    LaunchedEffect(Unit) {
        viewModel.eventFlow.collect { event ->
            when (event) {
                is RulesEvent.ExportReady -> {
                    pendingExport = event.json
                    exportLauncher.launch("clearsms-rules.json")
                }
                is RulesEvent.ShareReady -> {
                    val intent =
                        Intent(Intent.ACTION_SEND).apply {
                            type = "message/rfc822"
                            putExtra(Intent.EXTRA_EMAIL, arrayOf("rules@clearsms.app"))
                            putExtra(Intent.EXTRA_SUBJECT, shareSubject)
                            putExtra(Intent.EXTRA_TEXT, event.json)
                        }
                    context.startActivity(Intent.createChooser(intent, shareSubject))
                }
                is RulesEvent.ImportFinished ->
                    snackbarHostState.showSnackbar(if (event.success) importSucceeded else importFailed)
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.rules_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Outlined.ArrowBack,
                            contentDescription = stringResource(R.string.action_back),
                        )
                    }
                },
                actions = {
                    IconButton(onClick = { importLauncher.launch(arrayOf("application/json", "text/plain")) }) {
                        Icon(
                            Icons.Outlined.FileDownload,
                            contentDescription = stringResource(R.string.rules_import),
                        )
                    }
                    IconButton(onClick = viewModel::export) {
                        Icon(
                            Icons.Outlined.FileUpload,
                            contentDescription = stringResource(R.string.rules_export),
                        )
                    }
                    IconButton(onClick = viewModel::shareWithDeveloper) {
                        Icon(
                            Icons.Outlined.Share,
                            contentDescription = stringResource(R.string.rules_share),
                        )
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
        floatingActionButton = {
            FloatingActionButton(onClick = onCreateRule) {
                Icon(Icons.Outlined.Add, contentDescription = stringResource(R.string.rules_new_rule))
            }
        },
    ) { padding ->
        if (state.loaded && state.builtinRules.isEmpty() && state.userRules.isEmpty()) {
            EmptyState(
                icon = Icons.AutoMirrored.Outlined.Rule,
                title = stringResource(R.string.rules_empty_title),
                subtitle = stringResource(R.string.rules_empty_subtitle),
                modifier = Modifier.padding(padding),
            )
            return@Scaffold
        }
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(bottom = 88.dp),
        ) {
            if (state.userRules.isNotEmpty()) {
                item(key = "user_header") {
                    Text(
                        text = stringResource(R.string.rules_user_section),
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                    )
                }
                items(state.userRules, key = { "user_${it.id}" }) { rule ->
                    RuleRow(
                        rule = rule,
                        onToggle = { viewModel.setEnabled(rule, it) },
                        onDelete = { viewModel.deleteUserRule(rule.id) },
                    )
                }
            }
            item(key = "builtin_header") {
                Text(
                    text = stringResource(R.string.rules_builtin_section),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                )
            }
            items(state.builtinRules, key = { "builtin_${it.id}" }) { rule ->
                RuleRow(
                    rule = rule,
                    onToggle = { viewModel.setEnabled(rule, it) },
                    onDelete = null,
                )
            }
        }
    }
}

@Composable
private fun RuleRow(
    rule: RuleItem,
    onToggle: (Boolean) -> Unit,
    onDelete: (() -> Unit)?,
) {
    ListItem(
        headlineContent = { Text(rule.name) },
        supportingContent = {
            Text(
                text = rule.id,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        },
        trailingContent = {
            androidx.compose.foundation.layout.Row {
                if (onDelete != null) {
                    IconButton(onClick = onDelete) {
                        Icon(
                            Icons.Outlined.Delete,
                            contentDescription = stringResource(R.string.ui_action_delete),
                        )
                    }
                }
                Switch(
                    checked = rule.enabled,
                    onCheckedChange = onToggle,
                )
            }
        },
    )
}
