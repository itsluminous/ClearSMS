package app.clearsms.ui.settings

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.clearsms.R
import app.clearsms.domain.model.Category
import app.clearsms.domain.model.NotificationAction
import app.clearsms.domain.model.OtpAutoDeletePolicy
import app.clearsms.domain.model.OtpDisplaySize
import app.clearsms.domain.model.StartDestination
import app.clearsms.domain.model.SummaryFrequency
import app.clearsms.domain.model.SwipeAction
import app.clearsms.domain.model.ThemeMode
import app.clearsms.ui.common.BackupFrequency
import app.clearsms.ui.components.displayName

private enum class SettingsDialog {
    THEME,
    SUMMARY,
    NOTIFICATION_ACTIONS,
    SWIPE_START,
    SWIPE_END,
    DEFAULT_SCREEN,
    DEFAULT_FILTER,
    OTP_DELETE,
    OTP_SIZE,
    SIGNATURE,
    BLOCK_LIST,
    BACKUP_FREQUENCY,
    SORT_CONFIRM,
}

/** Settings root: every section from the product spec. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    onManageRules: () -> Unit,
    onPrivacyPolicy: () -> Unit,
    onLicenses: () -> Unit,
    onPermissions: () -> Unit,
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    var dialog by remember { mutableStateOf<SettingsDialog?>(null) }

    val backupDone = stringResource(R.string.settings_backup_done)
    val backupFailed = stringResource(R.string.settings_backup_failed)
    val restoreDone = stringResource(R.string.settings_restore_done)
    val restoreFailed = stringResource(R.string.settings_restore_failed)
    val sortDone = stringResource(R.string.settings_sort_done)

    val backupLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
            if (uri != null) viewModel.backupTo(uri)
        }
    val restoreLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) viewModel.restoreFrom(uri)
        }

    LaunchedEffect(Unit) {
        viewModel.eventFlow.collect { event ->
            snackbarHostState.showSnackbar(
                when (event) {
                    SettingsEvent.BackupDone -> backupDone
                    SettingsEvent.BackupFailed -> backupFailed
                    SettingsEvent.RestoreDone -> restoreDone
                    SettingsEvent.RestoreFailed -> restoreFailed
                    SettingsEvent.SortDone -> sortDone
                },
            )
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.settings_title)) },
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
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { padding ->
        Column(
            modifier =
                Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .verticalScroll(rememberScrollState()),
        ) {
            if (state.busy || state.sorting) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }

            SectionHeader(stringResource(R.string.settings_section_blocking))
            SettingRow(
                title = stringResource(R.string.settings_block_list),
                subtitle = stringResource(R.string.settings_block_list_summary, state.blockedSenders.size),
                onClick = { dialog = SettingsDialog.BLOCK_LIST },
            )

            SectionHeader(stringResource(R.string.settings_section_backup))
            SettingRow(
                title = stringResource(R.string.settings_backup_now),
                subtitle = stringResource(R.string.settings_backup_now_summary),
                onClick = { backupLauncher.launch("clearsms-backup.json") },
            )
            SettingRow(
                title = stringResource(R.string.settings_restore),
                subtitle = stringResource(R.string.settings_restore_summary),
                onClick = { restoreLauncher.launch(arrayOf("application/json", "text/plain")) },
            )
            // TODO: backupFrequency is written here but not consumed yet — the
            //  platform stage must schedule/cancel BackupWorker from it (OFF = no
            //  periodic backups).
            SettingRow(
                title = stringResource(R.string.settings_backup_frequency),
                subtitle = backupFrequencyLabel(state.backupFrequency),
                onClick = { dialog = SettingsDialog.BACKUP_FREQUENCY },
            )

            SectionHeader(stringResource(R.string.settings_section_appearance))
            SettingRow(
                title = stringResource(R.string.settings_theme),
                subtitle = themeLabel(state.theme),
                onClick = { dialog = SettingsDialog.THEME },
            )
            ToggleRow(
                title = stringResource(R.string.settings_dynamic_color),
                subtitle = stringResource(R.string.settings_dynamic_color_summary),
                checked = state.dynamicColor,
                onToggle = viewModel::setDynamicColor,
            )
            ToggleRow(
                title = stringResource(R.string.settings_show_rich_avatars),
                subtitle =
                    stringResource(
                        if (state.showRichAvatars) {
                            R.string.settings_show_rich_avatars_on
                        } else {
                            R.string.settings_show_rich_avatars_off
                        },
                    ),
                checked = state.showRichAvatars,
                onToggle = viewModel::setShowRichAvatars,
            )
            ToggleRow(
                title = stringResource(R.string.settings_show_transaction_details),
                subtitle = stringResource(R.string.settings_show_transaction_details_summary),
                checked = state.showTransactionDetails,
                onToggle = viewModel::setShowTransactionDetails,
            )

            SectionHeader(stringResource(R.string.settings_section_notification))
            // TODO: deliveryReports is written here but not consumed yet — the
            //  platform stage must read it in SmsSender to request delivery
            //  status for outgoing messages.
            ToggleRow(
                title = stringResource(R.string.settings_delivery_reports),
                subtitle = stringResource(R.string.settings_delivery_reports_summary),
                checked = state.deliveryReports,
                onToggle = viewModel::setDeliveryReports,
            )
            SettingRow(
                title = stringResource(R.string.settings_notification_actions),
                subtitle = notificationActionsSummary(state.notificationActions),
                onClick = { dialog = SettingsDialog.NOTIFICATION_ACTIONS },
            )
            ToggleRow(
                title = stringResource(R.string.settings_transaction_notifications),
                subtitle = stringResource(R.string.settings_transaction_notifications_summary),
                checked = state.transactionNotifications,
                onToggle = viewModel::setTransactionNotifications,
            )
            SettingRow(
                title = stringResource(R.string.settings_summary),
                subtitle = summaryLabel(state.summaryFrequency),
                onClick = { dialog = SettingsDialog.SUMMARY },
            )

            SectionHeader(stringResource(R.string.settings_section_gestures))
            SettingRow(
                title = stringResource(R.string.settings_swipe_right),
                subtitle = swipeActionLabel(state.swipeActionStart),
                onClick = { dialog = SettingsDialog.SWIPE_START },
            )
            SettingRow(
                title = stringResource(R.string.settings_swipe_left),
                subtitle = swipeActionLabel(state.swipeActionEnd),
                onClick = { dialog = SettingsDialog.SWIPE_END },
            )

            SectionHeader(stringResource(R.string.settings_section_startup))
            SettingRow(
                title = stringResource(R.string.settings_default_screen),
                subtitle = destinationLabel(state.defaultDestination),
                onClick = { dialog = SettingsDialog.DEFAULT_SCREEN },
            )
            SettingRow(
                title = stringResource(R.string.settings_default_inbox_filter),
                subtitle = inboxFilterLabel(state.defaultInboxFilter),
                onClick = { dialog = SettingsDialog.DEFAULT_FILTER },
            )

            SectionHeader(stringResource(R.string.settings_section_sort))
            SettingRow(
                title = stringResource(R.string.settings_sort_again),
                subtitle = stringResource(R.string.settings_sort_again_summary),
                onClick = { dialog = SettingsDialog.SORT_CONFIRM },
            )

            SectionHeader(stringResource(R.string.settings_section_otp))
            ToggleRow(
                title = stringResource(R.string.settings_otp_auto_copy),
                subtitle = stringResource(R.string.settings_otp_auto_copy_summary),
                checked = state.otpAutoCopy,
                onToggle = viewModel::setOtpAutoCopy,
            )
            SettingRow(
                title = stringResource(R.string.settings_otp_auto_delete),
                subtitle = otpDeleteLabel(state.otpAutoDeletePolicy),
                onClick = { dialog = SettingsDialog.OTP_DELETE },
            )
            SettingRow(
                title = stringResource(R.string.settings_otp_size),
                subtitle = otpSizeLabel(state.otpDisplaySize),
                onClick = { dialog = SettingsDialog.OTP_SIZE },
            )

            SectionHeader(stringResource(R.string.settings_section_rules))
            SettingRow(
                title = stringResource(R.string.settings_manage_rules),
                subtitle = stringResource(R.string.settings_manage_rules_summary),
                onClick = onManageRules,
            )

            SectionHeader(stringResource(R.string.settings_section_signature))
            SettingRow(
                title = stringResource(R.string.settings_signature),
                subtitle =
                    state.signature.ifBlank { stringResource(R.string.settings_signature_disabled) },
                onClick = { dialog = SettingsDialog.SIGNATURE },
            )

            SectionHeader(stringResource(R.string.settings_section_about))
            SettingRow(
                title = stringResource(R.string.settings_version),
                subtitle = appVersion(),
                onClick = {},
            )
            SettingRow(
                title = stringResource(R.string.settings_permissions),
                subtitle = stringResource(R.string.settings_permissions_summary),
                onClick = onPermissions,
            )
            SettingRow(
                title = stringResource(R.string.settings_privacy_policy),
                subtitle = stringResource(R.string.settings_privacy_policy_summary),
                onClick = onPrivacyPolicy,
            )
            SettingRow(
                title = stringResource(R.string.settings_licenses),
                subtitle = stringResource(R.string.settings_licenses_summary),
                onClick = onLicenses,
            )
        }
    }

    when (dialog) {
        SettingsDialog.THEME ->
            RadioDialog(
                title = stringResource(R.string.settings_theme),
                options = ThemeMode.entries.map { it to themeLabel(it) },
                selected = state.theme,
                onSelect = {
                    viewModel.setTheme(it)
                    dialog = null
                },
                onDismiss = { dialog = null },
            )
        SettingsDialog.SUMMARY ->
            RadioDialog(
                title = stringResource(R.string.settings_summary),
                options = SummaryFrequency.entries.map { it to summaryLabel(it) },
                selected = state.summaryFrequency,
                onSelect = {
                    viewModel.setSummaryFrequency(it)
                    dialog = null
                },
                onDismiss = { dialog = null },
            )
        SettingsDialog.NOTIFICATION_ACTIONS ->
            NotificationActionsDialog(
                selected = state.notificationActions,
                onConfirm = {
                    viewModel.setNotificationActions(it)
                    dialog = null
                },
                onDismiss = { dialog = null },
            )
        SettingsDialog.SWIPE_START ->
            RadioDialog(
                title = stringResource(R.string.settings_swipe_right),
                options = SwipeAction.entries.map { it to swipeActionLabel(it) },
                selected = state.swipeActionStart,
                onSelect = {
                    viewModel.setSwipeActionStart(it)
                    dialog = null
                },
                onDismiss = { dialog = null },
            )
        SettingsDialog.SWIPE_END ->
            RadioDialog(
                title = stringResource(R.string.settings_swipe_left),
                options = SwipeAction.entries.map { it to swipeActionLabel(it) },
                selected = state.swipeActionEnd,
                onSelect = {
                    viewModel.setSwipeActionEnd(it)
                    dialog = null
                },
                onDismiss = { dialog = null },
            )
        SettingsDialog.DEFAULT_SCREEN ->
            RadioDialog(
                title = stringResource(R.string.settings_default_screen),
                options = StartDestination.entries.map { it to destinationLabel(it) },
                selected = state.defaultDestination,
                onSelect = {
                    viewModel.setDefaultDestination(it)
                    dialog = null
                },
                onDismiss = { dialog = null },
            )
        SettingsDialog.DEFAULT_FILTER ->
            RadioDialog(
                title = stringResource(R.string.settings_default_inbox_filter),
                options =
                    (listOf<Category?>(null) + Category.entries).map { it to inboxFilterLabel(it) },
                selected = state.defaultInboxFilter,
                onSelect = {
                    viewModel.setDefaultInboxFilter(it)
                    dialog = null
                },
                onDismiss = { dialog = null },
            )
        SettingsDialog.OTP_DELETE ->
            RadioDialog(
                title = stringResource(R.string.settings_otp_auto_delete),
                options = OtpAutoDeletePolicy.entries.map { it to otpDeleteLabel(it) },
                selected = state.otpAutoDeletePolicy,
                onSelect = {
                    viewModel.setOtpAutoDeletePolicy(it)
                    dialog = null
                },
                onDismiss = { dialog = null },
            )
        SettingsDialog.OTP_SIZE ->
            OtpSizeDialog(
                selected = state.otpDisplaySize,
                onSelect = {
                    viewModel.setOtpDisplaySize(it)
                    dialog = null
                },
                onDismiss = { dialog = null },
            )
        SettingsDialog.SIGNATURE ->
            SignatureDialog(
                initial = state.signature,
                onConfirm = {
                    viewModel.setSignature(it)
                    dialog = null
                },
                onDismiss = { dialog = null },
            )
        SettingsDialog.BLOCK_LIST ->
            BlockListDialog(
                blocked = state.blockedSenders,
                onBlock = viewModel::blockSender,
                onUnblock = viewModel::unblockSender,
                onDismiss = { dialog = null },
            )
        SettingsDialog.BACKUP_FREQUENCY ->
            RadioDialog(
                title = stringResource(R.string.settings_backup_frequency),
                options = BackupFrequency.entries.map { it to backupFrequencyLabel(it) },
                selected = state.backupFrequency,
                onSelect = {
                    viewModel.setBackupFrequency(it)
                    dialog = null
                },
                onDismiss = { dialog = null },
            )
        SettingsDialog.SORT_CONFIRM ->
            AlertDialog(
                onDismissRequest = { dialog = null },
                title = { Text(stringResource(R.string.settings_sort_again)) },
                text = { Text(stringResource(R.string.settings_sort_confirm)) },
                confirmButton = {
                    TextButton(
                        onClick = {
                            viewModel.sortInboxAgain()
                            dialog = null
                        },
                    ) { Text(stringResource(R.string.settings_sort_go)) }
                },
                dismissButton = {
                    TextButton(onClick = { dialog = null }) { Text(stringResource(R.string.action_cancel)) }
                },
            )
        null -> Unit
    }
}

@Composable
private fun SectionHeader(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 16.dp, top = 20.dp, bottom = 4.dp),
    )
}

@Composable
private fun SettingRow(
    title: String,
    subtitle: String,
    onClick: () -> Unit,
) {
    ListItem(
        modifier = Modifier.clickable(onClick = onClick),
        headlineContent = { Text(title) },
        supportingContent = {
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        },
    )
}

@Composable
private fun ToggleRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onToggle: (Boolean) -> Unit,
) {
    ListItem(
        modifier = Modifier.clickable { onToggle(!checked) },
        headlineContent = { Text(title) },
        supportingContent = {
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        },
        trailingContent = { Switch(checked = checked, onCheckedChange = onToggle) },
    )
}

@Composable
private fun <T> RadioDialog(
    title: String,
    options: List<Pair<T, String>>,
    selected: T,
    onSelect: (T) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                options.forEach { (value, label) ->
                    Row(
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .heightIn(min = 48.dp)
                                .selectable(
                                    selected = selected == value,
                                    onClick = { onSelect(value) },
                                    role = Role.RadioButton,
                                ).padding(vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = selected == value, onClick = null)
                        Spacer(Modifier.padding(horizontal = 6.dp))
                        Text(label)
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
        },
    )
}

/**
 * Multi-select picker for notification action buttons. Android renders at
 * most 3 actions on a notification, so selection is capped at [MAX_NOTIFICATION_ACTIONS].
 */
@Composable
private fun NotificationActionsDialog(
    selected: Set<NotificationAction>,
    onConfirm: (Set<NotificationAction>) -> Unit,
    onDismiss: () -> Unit,
) {
    var current by remember { mutableStateOf(selected) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.settings_notification_actions)) },
        text = {
            Column {
                Text(
                    text = stringResource(R.string.settings_notification_actions_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                NotificationAction.entries.forEach { action ->
                    val checked = action in current
                    val enabled = checked || current.size < MAX_NOTIFICATION_ACTIONS
                    Row(
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .heightIn(min = 48.dp)
                                .toggleable(
                                    value = checked,
                                    enabled = enabled,
                                    onValueChange = {
                                        current = if (it) current + action else current - action
                                    },
                                    role = Role.Checkbox,
                                ).padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Checkbox(checked = checked, onCheckedChange = null, enabled = enabled)
                        Spacer(Modifier.padding(horizontal = 6.dp))
                        Text(notificationActionLabel(action))
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(current) }) { Text(stringResource(R.string.action_save)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
        },
    )
}

private const val MAX_NOTIFICATION_ACTIONS = 3

/** OTP size picker with a live preview of the digit size. */
@Composable
private fun OtpSizeDialog(
    selected: OtpDisplaySize,
    onSelect: (OtpDisplaySize) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.settings_otp_size)) },
        text = {
            Column {
                OtpDisplaySize.entries.forEach { size ->
                    Row(
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .selectable(
                                    selected = selected == size,
                                    onClick = { onSelect(size) },
                                    role = Role.RadioButton,
                                ).padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = selected == size, onClick = null)
                        Spacer(Modifier.padding(horizontal = 6.dp))
                        Column {
                            Text(otpSizeLabel(size))
                            Text(
                                text = "123456",
                                fontFamily = FontFamily.Monospace,
                                fontSize = otpPreviewSizeSp(size).sp,
                            )
                        }
                    }
                    HorizontalDivider()
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
        },
    )
}

@Composable
private fun SignatureDialog(
    initial: String,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var text by rememberSaveable { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.settings_signature)) },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                label = { Text(stringResource(R.string.settings_signature_hint)) },
            )
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(text) }) { Text(stringResource(R.string.action_save)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
        },
    )
}

@Composable
private fun BlockListDialog(
    blocked: List<String>,
    onBlock: (String) -> Unit,
    onUnblock: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var newSender by rememberSaveable { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.settings_block_list)) },
        text = {
            Column {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = newSender,
                        onValueChange = { newSender = it },
                        modifier = Modifier.weight(1f),
                        label = { Text(stringResource(R.string.settings_block_add_hint)) },
                        singleLine = true,
                    )
                    TextButton(
                        onClick = {
                            if (newSender.isNotBlank()) {
                                onBlock(newSender.trim())
                                newSender = ""
                            }
                        },
                    ) { Text(stringResource(R.string.settings_block_add)) }
                }
                blocked.forEach { sender ->
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(text = sender, modifier = Modifier.weight(1f))
                        TextButton(onClick = { onUnblock(sender) }) {
                            Text(stringResource(R.string.settings_unblock))
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_done)) }
        },
    )
}

@Composable
private fun notificationActionLabel(action: NotificationAction): String =
    when (action) {
        NotificationAction.MARK_READ -> stringResource(R.string.action_mark_read)
        NotificationAction.DELETE -> stringResource(R.string.ui_action_delete)
        NotificationAction.REPLY -> stringResource(R.string.notification_action_reply)
        NotificationAction.COPY_OTP -> stringResource(R.string.action_copy_otp)
        NotificationAction.SHARE_OTP -> stringResource(R.string.notification_action_share_otp)
    }

@Composable
private fun notificationActionsSummary(actions: Set<NotificationAction>): String =
    if (actions.isEmpty()) {
        stringResource(R.string.settings_notification_actions_none)
    } else {
        NotificationAction.entries
            .filter { it in actions }
            .map { notificationActionLabel(it) }
            .joinToString(separator = ", ")
    }

@Composable
private fun swipeActionLabel(action: SwipeAction): String =
    when (action) {
        SwipeAction.NONE -> stringResource(R.string.swipe_action_none)
        SwipeAction.TOGGLE_READ -> stringResource(R.string.swipe_action_toggle_read)
        SwipeAction.DELETE -> stringResource(R.string.ui_action_delete)
        SwipeAction.ARCHIVE -> stringResource(R.string.action_archive)
    }

@Composable
private fun destinationLabel(destination: StartDestination): String =
    when (destination) {
        StartDestination.INBOX -> stringResource(R.string.nav_inbox)
        StartDestination.FINANCE -> stringResource(R.string.nav_finance)
        StartDestination.ALERTS -> stringResource(R.string.nav_alerts)
    }

@Composable
private fun inboxFilterLabel(filter: Category?): String = filter?.displayName() ?: stringResource(R.string.settings_filter_all)

@Composable
private fun themeLabel(mode: ThemeMode): String =
    when (mode) {
        ThemeMode.SYSTEM -> stringResource(R.string.theme_system)
        ThemeMode.LIGHT -> stringResource(R.string.theme_light)
        ThemeMode.DARK -> stringResource(R.string.theme_dark)
    }

@Composable
private fun summaryLabel(frequency: SummaryFrequency): String =
    when (frequency) {
        SummaryFrequency.OFF -> stringResource(R.string.summary_off)
        SummaryFrequency.DAILY -> stringResource(R.string.summary_daily)
        SummaryFrequency.WEEKLY -> stringResource(R.string.summary_weekly)
    }

@Composable
private fun backupFrequencyLabel(frequency: BackupFrequency): String =
    when (frequency) {
        BackupFrequency.OFF -> stringResource(R.string.summary_off)
        BackupFrequency.DAILY -> stringResource(R.string.summary_daily)
        BackupFrequency.WEEKLY -> stringResource(R.string.summary_weekly)
    }

@Composable
private fun otpDeleteLabel(policy: OtpAutoDeletePolicy): String =
    when (policy) {
        OtpAutoDeletePolicy.NEVER -> stringResource(R.string.otp_delete_never)
        OtpAutoDeletePolicy.HOURS_24 -> stringResource(R.string.otp_delete_24h)
        OtpAutoDeletePolicy.DAYS_3 -> stringResource(R.string.otp_delete_3d)
        OtpAutoDeletePolicy.DAYS_7 -> stringResource(R.string.otp_delete_7d)
    }

@Composable
private fun otpSizeLabel(size: OtpDisplaySize): String =
    when (size) {
        OtpDisplaySize.DEFAULT -> stringResource(R.string.otp_size_default)
        OtpDisplaySize.OPTION_A -> stringResource(R.string.otp_size_a)
        OtpDisplaySize.OPTION_B -> stringResource(R.string.otp_size_b)
        OtpDisplaySize.OPTION_C -> stringResource(R.string.otp_size_c)
        OtpDisplaySize.OPTION_D -> stringResource(R.string.otp_size_d)
    }

private fun otpPreviewSizeSp(size: OtpDisplaySize): Int =
    when (size) {
        OtpDisplaySize.DEFAULT -> 20
        OtpDisplaySize.OPTION_A -> 16
        OtpDisplaySize.OPTION_B -> 24
        OtpDisplaySize.OPTION_C -> 30
        OtpDisplaySize.OPTION_D -> 36
    }

@Composable
private fun appVersion(): String {
    val context = androidx.compose.ui.platform.LocalContext.current
    return try {
        context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "—"
    } catch (_: Exception) {
        "—"
    }
}
