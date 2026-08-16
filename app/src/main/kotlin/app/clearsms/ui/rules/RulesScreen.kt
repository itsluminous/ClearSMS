package app.clearsms.ui.rules

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.Rule
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.FileDownload
import androidx.compose.material.icons.outlined.FileUpload
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.clearsms.R
import app.clearsms.ui.components.EmptyState
import app.clearsms.ui.components.TooltipIconButton

/** Rule management: builtin/user groups, enable toggles, SAF import/export, share. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RulesScreen(
    onBack: () -> Unit,
    onCreateRule: () -> Unit,
    onEditRule: (String) -> Unit,
    onDuplicateRule: (String) -> Unit,
    viewModel: RulesViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val detail by viewModel.ruleDetail.collectAsStateWithLifecycle()
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
                    TooltipIconButton(
                        label = stringResource(R.string.action_back),
                        onClick = onBack,
                        icon = Icons.AutoMirrored.Outlined.ArrowBack,
                    )
                },
                actions = {
                    TooltipIconButton(
                        label = stringResource(R.string.rules_import),
                        onClick = { importLauncher.launch(arrayOf("application/json", "text/plain")) },
                        icon = Icons.Outlined.FileDownload,
                    )
                    TooltipIconButton(
                        label = stringResource(R.string.rules_export),
                        onClick = viewModel::export,
                        icon = Icons.Outlined.FileUpload,
                    )
                    TooltipIconButton(
                        label = stringResource(R.string.rules_share),
                        onClick = viewModel::shareWithDeveloper,
                        icon = Icons.Outlined.Share,
                    )
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
        var query by rememberSaveable { mutableStateOf("") }
        val shownUserRules = filterRules(state.userRules, query)
        val shownBuiltinRules = filterRules(state.builtinRules, query)
        if (state.loaded && state.builtinRules.isEmpty() && state.userRules.isEmpty()) {
            EmptyState(
                icon = Icons.AutoMirrored.Outlined.Rule,
                title = stringResource(R.string.rules_empty_title),
                subtitle = stringResource(R.string.rules_empty_subtitle),
                modifier = Modifier.padding(padding),
            )
            return@Scaffold
        }
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            // Search pill: rounded like the inbox filter pills, filtering both
            // sections live on name or rule id.
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                placeholder = { Text(stringResource(R.string.rules_search_hint)) },
                leadingIcon = { Icon(Icons.Outlined.Search, contentDescription = null) },
                trailingIcon = {
                    if (query.isNotEmpty()) {
                        TooltipIconButton(
                            label = stringResource(R.string.rules_search_clear),
                            onClick = { query = "" },
                            icon = Icons.Outlined.Close,
                        )
                    }
                },
                singleLine = true,
                shape = RoundedCornerShape(28.dp),
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp),
            )
            if (query.isNotBlank() && shownUserRules.isEmpty() && shownBuiltinRules.isEmpty()) {
                Text(
                    text = stringResource(R.string.rules_search_empty, query),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(24.dp),
                )
            }
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(bottom = 88.dp),
            ) {
                if (shownUserRules.isNotEmpty()) {
                    item(key = "user_header") {
                        Text(
                            text = stringResource(R.string.rules_user_section),
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                        )
                    }
                    items(shownUserRules, key = { "user_${it.id}" }) { rule ->
                        RuleRow(
                            rule = rule,
                            // A parked (disabled) rule is not in the database, so
                            // there is nothing for the editor to load.
                            onClick = if (rule.enabled) ({ onEditRule(rule.id) }) else null,
                            onToggle = { viewModel.setEnabled(rule, it) },
                            onDelete = { viewModel.deleteUserRule(rule.id) },
                        )
                    }
                }
                if (shownBuiltinRules.isNotEmpty()) {
                    item(key = "builtin_header") {
                        Text(
                            text = stringResource(R.string.rules_builtin_section),
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                        )
                    }
                }
                items(shownBuiltinRules, key = { "builtin_${it.id}" }) { rule ->
                    RuleRow(
                        rule = rule,
                        onClick = if (rule.enabled) ({ viewModel.showDetail(rule.id) }) else null,
                        onToggle = { viewModel.setEnabled(rule, it) },
                        onDelete = null,
                    )
                }
            }
        }
    }

    detail?.let { rule ->
        RuleDetailDialog(
            detail = rule,
            onDismiss = viewModel::dismissDetail,
            onDuplicate = {
                viewModel.dismissDetail()
                onDuplicateRule(rule.id)
            },
        )
    }
}

@Composable
private fun RuleRow(
    rule: RuleItem,
    onClick: (() -> Unit)?,
    onToggle: (Boolean) -> Unit,
    onDelete: (() -> Unit)?,
) {
    ListItem(
        // The switch and delete button consume their own taps, so the row
        // click never swallows the toggle.
        modifier = if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier,
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

/**
 * Read-only detail for a tapped bundled rule. Bundled rules are never
 * edited in place (the bundled set must stay identical to the shipped
 * asset), so the only action is duplicating into a user-owned copy.
 */
@Composable
private fun RuleDetailDialog(
    detail: RuleDetail,
    onDismiss: () -> Unit,
    onDuplicate: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(detail.name) },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                DetailField(stringResource(R.string.rule_detail_id), detail.id)
                DetailField(stringResource(R.string.rule_detail_priority), detail.priority.toString())
                DetailField(
                    stringResource(R.string.rule_detail_category),
                    listOfNotNull(detail.category, detail.subCategory).joinToString(" / "),
                )
                detail.senderPattern?.let { DetailField(stringResource(R.string.rule_detail_sender_pattern), it) }
                detail.bodyPattern?.let { DetailField(stringResource(R.string.rule_detail_body_pattern), it) }
                if (detail.mustContain.isNotEmpty()) {
                    DetailField(stringResource(R.string.rule_detail_must_contain), detail.mustContain.joinToString(", "))
                }
                if (detail.mustNotContain.isNotEmpty()) {
                    DetailField(
                        stringResource(R.string.rule_detail_must_not_contain),
                        detail.mustNotContain.joinToString(", "),
                    )
                }
                if (detail.guardsNone.isNotEmpty()) {
                    DetailField(stringResource(R.string.rule_detail_guards), detail.guardsNone.joinToString(", "))
                }
                if (detail.extract.isNotEmpty()) {
                    DetailField(
                        stringResource(R.string.rule_detail_extract),
                        detail.extract.entries.joinToString("\n") { "${it.key} = ${it.value}" },
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDuplicate) {
                Text(stringResource(R.string.rule_detail_duplicate))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.ui_action_close))
            }
        },
    )
}

@Composable
private fun DetailField(
    label: String,
    value: String,
) {
    Column {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
        )
    }
}
