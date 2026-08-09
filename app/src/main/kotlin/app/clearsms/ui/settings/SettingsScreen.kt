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
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.DeleteSweep
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Search
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
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.clearsms.BuildConfig
import app.clearsms.R
import app.clearsms.domain.model.Category
import app.clearsms.domain.model.FinanceTab
import app.clearsms.domain.model.LogoBackground
import app.clearsms.domain.model.NotificationAction
import app.clearsms.domain.model.OtpAutoDeletePolicy
import app.clearsms.domain.model.OtpDisplaySize
import app.clearsms.domain.model.StartDestination
import app.clearsms.domain.model.SwipeAction
import app.clearsms.domain.model.ThemeMode
import app.clearsms.ui.alerts.AlertFilter
import app.clearsms.ui.alerts.displayName
import app.clearsms.ui.common.BackupFrequency
import app.clearsms.ui.components.DeleteConfirmationDialog
import app.clearsms.ui.components.displayName
import app.clearsms.ui.components.otpPreviewFontSp
import app.clearsms.ui.finance.displayName
import app.clearsms.ui.navigation.orderedPills
import kotlinx.coroutines.launch

private enum class SettingsDialog {
    THEME,
    INBOX_PILL_ORDER,
    FINANCE_PILL_ORDER,
    ALERTS_PILL_ORDER,
    LOGO_BACKGROUND,
    NOTIFICATION_ACTIONS,
    SWIPE_START,
    SWIPE_END,
    DEFAULT_SCREEN,
    DEFAULT_FILTER,
    DEFAULT_FINANCE_FILTER,
    OTP_DELETE,
    OTP_SIZE,
    CLEAR_OTP,
    SIGNATURE,
    BLOCK_LIST,
    BACKUP_FREQUENCY,
    SORT_CONFIRM,
}

/**
 * One settings row in the declarative list the screen renders and the
 * search filters. [title] and [summary] carry the resolved user-visible
 * strings so the search matches exactly what is on screen; [content] renders
 * the row itself (a plain row, a toggle, an action, or the inline sort
 * progress) with its behaviour unchanged. [section] is null for the
 * standalone entries that render below all sections without a header.
 */
private class SettingsRowEntry(
    val section: String?,
    val title: String,
    val summary: String,
    val content: @Composable () -> Unit,
)

/** Settings root: every section from the product spec, searchable from the top bar. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    onManageRules: () -> Unit,
    onArchived: () -> Unit,
    onPrivacyPolicy: () -> Unit,
    onLicenses: () -> Unit,
    onPermissions: () -> Unit,
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val pendingOtpClear by viewModel.pendingOtpClear.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    var dialog by remember { mutableStateOf<SettingsDialog?>(null) }
    var searchActive by rememberSaveable { mutableStateOf(false) }
    var query by rememberSaveable { mutableStateOf("") }

    val backupDone = stringResource(R.string.settings_backup_done)
    val backupFailed = stringResource(R.string.settings_backup_failed)
    val restoreFailed = stringResource(R.string.settings_restore_failed)
    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()

    // Source code / Donate rows: hand the URL to whatever app claims it; a
    // missing handler (no browser, no UPI app) surfaces a snackbar, never a crash.
    val linkNoHandler = stringResource(R.string.settings_link_no_handler)
    val openLink: (String) -> Unit = { url ->
        if (!ExternalLinks.open(context, url)) {
            scope.launch { snackbarHostState.showSnackbar(linkNoHandler) }
        }
    }

    val backupLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
            if (uri != null) viewModel.backupTo(uri)
        }
    val restoreLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) viewModel.restoreFrom(uri)
        }
    val settingsBackupLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
            if (uri != null) viewModel.backupSettingsTo(uri)
        }
    val settingsRestoreLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) viewModel.restoreSettingsFrom(uri)
        }

    LaunchedEffect(Unit) {
        viewModel.eventFlow.collect { event ->
            snackbarHostState.showSnackbar(
                when (event) {
                    SettingsEvent.BackupDone -> backupDone
                    SettingsEvent.BackupFailed -> backupFailed
                    is SettingsEvent.RestoreDone -> {
                        val r = event.result
                        buildString {
                            append(
                                context.getString(
                                    R.string.settings_restore_done_counts,
                                    r.messages,
                                    r.transactions,
                                    r.accounts,
                                    r.rules,
                                    r.reminders,
                                ),
                            )
                            if (r.defaultedValues > 0 || r.skippedRows > 0) {
                                append(' ')
                                append(
                                    context.getString(
                                        R.string.settings_restore_done_issues,
                                        r.defaultedValues,
                                        r.skippedRows,
                                    ),
                                )
                            }
                        }
                    }
                    is SettingsEvent.RestoreFailed ->
                        event.reason
                            ?.let { context.getString(R.string.settings_restore_failed_reason, it) }
                            ?: restoreFailed
                    SettingsEvent.SettingsBackupDone ->
                        context.getString(R.string.settings_backup_settings_done)
                    SettingsEvent.SettingsBackupFailed ->
                        context.getString(R.string.settings_backup_settings_failed)
                    is SettingsEvent.SettingsRestoreDone ->
                        buildString {
                            append(
                                context.getString(
                                    R.string.settings_restore_settings_done,
                                    event.result.applied,
                                ),
                            )
                            if (event.result.skipped > 0) {
                                append(' ')
                                append(
                                    context.getString(
                                        R.string.settings_restore_settings_skipped,
                                        event.result.skipped,
                                    ),
                                )
                            }
                        }
                    SettingsEvent.SettingsRestoreFailed ->
                        context.getString(R.string.settings_restore_settings_failed)
                    is SettingsEvent.SortDone ->
                        context.getString(R.string.settings_sort_done_count, event.count)
                    is SettingsEvent.OtpCleared ->
                        context.getString(R.string.settings_clear_otp_done, event.count)
                    SettingsEvent.OtpClearEmpty ->
                        context.getString(R.string.settings_clear_otp_empty)
                },
            )
        }
    }

    val rows =
        settingsRowEntries(
            state = state,
            viewModel = viewModel,
            openDialog = { dialog = it },
            onBackupNow = { backupLauncher.launch("clearsms-backup.json") },
            onRestore = { restoreLauncher.launch(arrayOf("application/json", "text/plain")) },
            onBackupSettings = { settingsBackupLauncher.launch("clearsms-settings.json") },
            onRestoreSettings = { settingsRestoreLauncher.launch(arrayOf("application/json", "text/plain")) },
            onManageRules = onManageRules,
            onArchived = onArchived,
            onPermissions = onPermissions,
            onPrivacyPolicy = onPrivacyPolicy,
            onLicenses = onLicenses,
            onOpenLink = openLink,
        )

    val searchFocus = remember { FocusRequester() }
    LaunchedEffect(searchActive) {
        if (searchActive) searchFocus.requestFocus()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    if (searchActive) {
                        TextField(
                            value = query,
                            onValueChange = { query = it },
                            placeholder = { Text(stringResource(R.string.settings_search_hint)) },
                            singleLine = true,
                            colors =
                                TextFieldDefaults.colors(
                                    focusedContainerColor = Color.Transparent,
                                    unfocusedContainerColor = Color.Transparent,
                                    focusedIndicatorColor = Color.Transparent,
                                    unfocusedIndicatorColor = Color.Transparent,
                                ),
                            modifier = Modifier.fillMaxWidth().focusRequester(searchFocus),
                        )
                    } else {
                        Text(stringResource(R.string.settings_title))
                    }
                },
                navigationIcon = {
                    IconButton(
                        onClick = {
                            if (searchActive) {
                                searchActive = false
                                query = ""
                            } else {
                                onBack()
                            }
                        },
                    ) {
                        Icon(
                            Icons.AutoMirrored.Outlined.ArrowBack,
                            contentDescription =
                                if (searchActive) {
                                    stringResource(R.string.settings_search_close)
                                } else {
                                    stringResource(R.string.action_back)
                                },
                        )
                    }
                },
                actions = {
                    if (searchActive) {
                        if (query.isNotEmpty()) {
                            IconButton(onClick = { query = "" }) {
                                Icon(
                                    Icons.Outlined.Close,
                                    contentDescription = stringResource(R.string.settings_search_clear),
                                )
                            }
                        }
                    } else {
                        IconButton(onClick = { searchActive = true }) {
                            Icon(
                                Icons.Outlined.Search,
                                contentDescription = stringResource(R.string.settings_search),
                            )
                        }
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
            if (state.busy) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }
            val effectiveQuery = if (searchActive) query else ""
            val visible = filterSettingsRows(rows, effectiveQuery, { it.title }, { it.summary })
            if (visible.isEmpty()) {
                Text(
                    text = stringResource(R.string.settings_search_empty, query),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(24.dp),
                )
            } else {
                // Matching rows keep their section header for context; the
                // trailing standalone entries (null section) get a divider
                // instead of a header so they read as their own block.
                var lastSection: String? = null
                var standaloneDividerShown = false
                visible.forEach { row ->
                    if (row.section == null) {
                        if (!standaloneDividerShown) {
                            standaloneDividerShown = true
                            HorizontalDivider(modifier = Modifier.padding(top = 16.dp))
                        }
                    } else if (row.section != lastSection) {
                        lastSection = row.section
                        SectionHeader(row.section)
                    }
                    row.content()
                }
            }
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
        SettingsDialog.INBOX_PILL_ORDER -> {
            val order by viewModel.inboxPillOrder.collectAsStateWithLifecycle()
            PillOrderDialog(
                title = stringResource(R.string.settings_pill_order),
                order = orderedPills(order, Category.entries.toList()),
                label = { it.displayName() },
                onConfirm = {
                    viewModel.setInboxPillOrder(it)
                    dialog = null
                },
                onReset = {
                    viewModel.resetInboxPillOrder()
                    dialog = null
                },
                onDismiss = { dialog = null },
            )
        }
        SettingsDialog.FINANCE_PILL_ORDER -> {
            val order by viewModel.financePillOrder.collectAsStateWithLifecycle()
            PillOrderDialog(
                title = stringResource(R.string.settings_pill_order),
                order = orderedPills(order, FinanceTab.entries.toList()),
                label = { it.displayName() },
                onConfirm = {
                    viewModel.setFinancePillOrder(it)
                    dialog = null
                },
                onReset = {
                    viewModel.resetFinancePillOrder()
                    dialog = null
                },
                onDismiss = { dialog = null },
            )
        }
        SettingsDialog.ALERTS_PILL_ORDER -> {
            val order by viewModel.alertsPillOrder.collectAsStateWithLifecycle()
            PillOrderDialog(
                title = stringResource(R.string.settings_pill_order),
                order = orderedPills(order, AlertFilter.entries.toList()),
                label = { it.displayName() },
                onConfirm = {
                    viewModel.setAlertsPillOrder(it)
                    dialog = null
                },
                onReset = {
                    viewModel.resetAlertsPillOrder()
                    dialog = null
                },
                onDismiss = { dialog = null },
            )
        }
        SettingsDialog.LOGO_BACKGROUND ->
            RadioDialog(
                title = stringResource(R.string.settings_logo_background),
                options = LogoBackground.entries.map { it to logoBackgroundLabel(it) },
                selected = state.logoBackground,
                onSelect = {
                    viewModel.setLogoBackground(it)
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
        SettingsDialog.DEFAULT_FINANCE_FILTER ->
            RadioDialog(
                title = stringResource(R.string.settings_default_finance_filter),
                options = FinanceTab.entries.map { it to it.displayName() },
                selected = state.defaultFinanceFilter,
                onSelect = {
                    viewModel.setDefaultFinanceFilter(it)
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
        SettingsDialog.CLEAR_OTP ->
            ClearOtpDialog(
                onContinue = { range ->
                    viewModel.requestClearOtp(range)
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

    // Confirm-before-delete for the manual OTP cleanup: reuses the shared
    // delete dialog because this also removes the messages from the system
    // SMS provider and cannot be undone.
    pendingOtpClear?.let { pending ->
        DeleteConfirmationDialog(
            title = stringResource(R.string.settings_clear_otp_confirm_title),
            text = stringResource(R.string.settings_clear_otp_confirm_text, pending.count),
            onConfirm = viewModel::confirmClearOtp,
            onDismiss = viewModel::dismissClearOtp,
        )
    }
}

/**
 * The full settings list as a declarative model: one entry per row, with
 * the resolved title/summary the search filters on. Section order and row
 * order come from [SettingsItem]'s declaration order (see SettingsCatalog),
 * so a plain unit test can assert the exact layout; this function only maps
 * each item to its behaviour.
 */
@Composable
private fun settingsRowEntries(
    state: SettingsUiState,
    viewModel: SettingsViewModel,
    openDialog: (SettingsDialog) -> Unit,
    onBackupNow: () -> Unit,
    onRestore: () -> Unit,
    onBackupSettings: () -> Unit,
    onRestoreSettings: () -> Unit,
    onManageRules: () -> Unit,
    onArchived: () -> Unit,
    onPermissions: () -> Unit,
    onPrivacyPolicy: () -> Unit,
    onLicenses: () -> Unit,
    onOpenLink: (String) -> Unit,
): List<SettingsRowEntry> {
    fun row(
        section: String?,
        title: String,
        summary: String,
        onClick: () -> Unit,
    ) = SettingsRowEntry(section, title, summary) {
        SettingRow(title = title, subtitle = summary, onClick = onClick)
    }

    fun toggle(
        section: String?,
        title: String,
        summary: String,
        checked: Boolean,
        onToggle: (Boolean) -> Unit,
    ) = SettingsRowEntry(section, title, summary) {
        ToggleRow(title = title, subtitle = summary, checked = checked, onToggle = onToggle)
    }

    return SettingsItem.entries.map { item ->
        val section = item.section?.let { stringResource(it.titleRes) }
        val title = stringResource(item.titleRes)
        when (item) {
            SettingsItem.ARCHIVED ->
                row(section, title, stringResource(R.string.settings_archived_summary), onArchived)
            SettingsItem.BLOCK_LIST ->
                row(section, title, stringResource(R.string.settings_block_list_summary, state.blockedSenders.size)) {
                    openDialog(SettingsDialog.BLOCK_LIST)
                }
            SettingsItem.SHOW_EXTRACTED_DETAILS ->
                toggle(
                    section = section,
                    title = title,
                    summary = stringResource(R.string.settings_show_transaction_details_summary),
                    checked = state.showTransactionDetails,
                    onToggle = viewModel::setShowTransactionDetails,
                )
            SettingsItem.THEME ->
                row(section, title, themeLabel(state.theme)) { openDialog(SettingsDialog.THEME) }
            SettingsItem.DYNAMIC_COLOR ->
                toggle(
                    section = section,
                    title = title,
                    summary = stringResource(R.string.settings_dynamic_color_summary),
                    checked = state.dynamicColor,
                    onToggle = viewModel::setDynamicColor,
                )
            SettingsItem.SHOW_RICH_AVATARS ->
                toggle(
                    section = section,
                    title = title,
                    summary =
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
            SettingsItem.LOGO_BACKGROUND ->
                row(section, title, logoBackgroundLabel(state.logoBackground)) {
                    openDialog(SettingsDialog.LOGO_BACKGROUND)
                }
            // TODO: deliveryReports is written here but not consumed yet — the
            //  platform stage must read it in SmsSender to request delivery
            //  status for outgoing messages.
            SettingsItem.DELIVERY_REPORTS ->
                toggle(
                    section = section,
                    title = title,
                    summary = stringResource(R.string.settings_delivery_reports_summary),
                    checked = state.deliveryReports,
                    onToggle = viewModel::setDeliveryReports,
                )
            SettingsItem.NOTIFICATION_ACTIONS ->
                row(section, title, notificationActionsSummary(state.notificationActions)) {
                    openDialog(SettingsDialog.NOTIFICATION_ACTIONS)
                }
            SettingsItem.TRANSACTION_NOTIFICATIONS ->
                toggle(
                    section = section,
                    title = title,
                    summary = stringResource(R.string.settings_transaction_notifications_summary),
                    checked = state.transactionNotifications,
                    onToggle = viewModel::setTransactionNotifications,
                )
            SettingsItem.OTP_AUTO_COPY ->
                toggle(
                    section = section,
                    title = title,
                    summary = stringResource(R.string.settings_otp_auto_copy_summary),
                    checked = state.otpAutoCopy,
                    onToggle = viewModel::setOtpAutoCopy,
                )
            SettingsItem.OTP_AUTO_DELETE ->
                row(section, title, otpDeleteLabel(state.otpAutoDeletePolicy)) {
                    openDialog(SettingsDialog.OTP_DELETE)
                }
            SettingsItem.OTP_SIZE ->
                row(section, title, otpSizeLabel(state.otpDisplaySize)) { openDialog(SettingsDialog.OTP_SIZE) }
            // One-shot ACTION, not a preference: the leading icon and the
            // "runs now" copy keep it visually distinct from "Auto delete
            // OTP" above, which is the recurring policy.
            SettingsItem.CLEAR_OTP -> {
                val clearOtpSummary = stringResource(R.string.settings_clear_otp_summary)
                SettingsRowEntry(section, title, clearOtpSummary) {
                    ActionRow(
                        icon = Icons.Outlined.DeleteSweep,
                        title = title,
                        subtitle = clearOtpSummary,
                        onClick = { openDialog(SettingsDialog.CLEAR_OTP) },
                    )
                }
            }
            SettingsItem.INBOX_PILL_ORDER ->
                row(section, title, stringResource(R.string.settings_pill_order_summary)) {
                    openDialog(SettingsDialog.INBOX_PILL_ORDER)
                }
            SettingsItem.DEFAULT_INBOX_FILTER ->
                row(section, title, inboxFilterLabel(state.defaultInboxFilter)) {
                    openDialog(SettingsDialog.DEFAULT_FILTER)
                }
            SettingsItem.SWIPE_RIGHT ->
                row(section, title, swipeActionLabel(state.swipeActionStart)) {
                    openDialog(SettingsDialog.SWIPE_START)
                }
            SettingsItem.SWIPE_LEFT ->
                row(section, title, swipeActionLabel(state.swipeActionEnd)) {
                    openDialog(SettingsDialog.SWIPE_END)
                }
            SettingsItem.SORT_AGAIN -> {
                val sortSummary = stringResource(R.string.settings_sort_again_summary)
                SettingsRowEntry(section, title, sortSummary) {
                    val sort = state.sortProgress
                    if (sort != null) {
                        // Running: determinate inline progress with "x of y", re-trigger
                        // disabled (row is not clickable), and a cancel affordance.
                        ListItem(
                            headlineContent = { Text(stringResource(R.string.settings_sort_running)) },
                            supportingContent = {
                                Column {
                                    LinearProgressIndicator(
                                        progress = { if (sort.total > 0) sort.processed / sort.total.toFloat() else 0f },
                                        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
                                    )
                                    Text(
                                        text =
                                            stringResource(
                                                R.string.settings_sort_progress,
                                                sort.processed,
                                                sort.total,
                                            ),
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            },
                            trailingContent = {
                                TextButton(onClick = viewModel::cancelSort) {
                                    Text(stringResource(R.string.action_cancel))
                                }
                            },
                        )
                    } else {
                        // Same convention as "Clear older OTPs": one-shot
                        // ACTION rows get a leading icon; preference and
                        // navigation rows stay plain.
                        ActionRow(
                            icon = Icons.Outlined.Refresh,
                            title = title,
                            subtitle = sortSummary,
                            onClick = { openDialog(SettingsDialog.SORT_CONFIRM) },
                        )
                    }
                }
            }
            SettingsItem.FINANCE_PILL_ORDER ->
                row(section, title, stringResource(R.string.settings_pill_order_summary)) {
                    openDialog(SettingsDialog.FINANCE_PILL_ORDER)
                }
            // Privacy, not Appearance: hiding balances behind the device lock is
            // a confidentiality control, not a cosmetic one — living under
            // Finance also keeps it visually distinct from the extracted-details
            // verbosity toggle, which users previously conflated with it.
            SettingsItem.SHOW_BALANCE ->
                toggle(
                    section = section,
                    title = title,
                    summary =
                        stringResource(
                            if (state.showBalance) {
                                R.string.settings_show_balance_on
                            } else {
                                R.string.settings_show_balance_off
                            },
                        ),
                    checked = state.showBalance,
                    onToggle = viewModel::setShowBalance,
                )
            SettingsItem.DEFAULT_FINANCE_FILTER ->
                row(section, title, state.defaultFinanceFilter.displayName()) {
                    openDialog(SettingsDialog.DEFAULT_FINANCE_FILTER)
                }
            SettingsItem.ALERTS_PILL_ORDER ->
                row(section, title, stringResource(R.string.settings_pill_order_summary)) {
                    openDialog(SettingsDialog.ALERTS_PILL_ORDER)
                }
            SettingsItem.DEFAULT_SCREEN ->
                row(section, title, destinationLabel(state.defaultDestination)) {
                    openDialog(SettingsDialog.DEFAULT_SCREEN)
                }
            SettingsItem.BACKUP_NOW ->
                row(section, title, stringResource(R.string.settings_backup_now_summary), onBackupNow)
            SettingsItem.RESTORE ->
                row(section, title, stringResource(R.string.settings_restore_summary), onRestore)
            SettingsItem.BACKUP_SETTINGS ->
                row(section, title, stringResource(R.string.settings_backup_settings_summary), onBackupSettings)
            SettingsItem.RESTORE_SETTINGS ->
                row(section, title, stringResource(R.string.settings_restore_settings_summary), onRestoreSettings)
            SettingsItem.BACKUP_FREQUENCY -> {
                val backupFrequencySummary = backupFrequencyLabel(state.backupFrequency)
                val autoBackupNote = stringResource(R.string.settings_auto_backup_note)
                SettingsRowEntry(section, title, backupFrequencySummary) {
                    SettingRow(
                        title = title,
                        subtitle = backupFrequencySummary,
                        onClick = { openDialog(SettingsDialog.BACKUP_FREQUENCY) },
                    )
                    Text(
                        text = autoBackupNote,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                    )
                }
            }
            SettingsItem.MANAGE_RULES ->
                row(section, title, stringResource(R.string.settings_manage_rules_summary), onManageRules)
            SettingsItem.SIGNATURE ->
                row(
                    section,
                    title,
                    state.signature.ifBlank { stringResource(R.string.settings_signature_disabled) },
                ) { openDialog(SettingsDialog.SIGNATURE) }
            SettingsItem.VERSION -> {
                // Interpolate the real build version so the link always lands
                // on this build's release notes — never a hardcoded tag.
                val url = stringResource(R.string.url_release_notes, BuildConfig.VERSION_NAME)
                row(section, title, appVersion()) { onOpenLink(url) }
            }
            SettingsItem.SOURCE_CODE -> {
                val url = stringResource(R.string.url_source_code)
                row(section, title, stringResource(R.string.settings_source_code_summary)) { onOpenLink(url) }
            }
            SettingsItem.UPI -> {
                val url = stringResource(R.string.url_donate_upi)
                row(section, title, stringResource(R.string.settings_donate_upi_summary)) { onOpenLink(url) }
            }
            SettingsItem.PAYPAL -> {
                val url = stringResource(R.string.url_donate_paypal)
                row(section, title, stringResource(R.string.settings_donate_paypal_summary)) { onOpenLink(url) }
            }
            SettingsItem.PERMISSIONS ->
                row(section, title, stringResource(R.string.settings_permissions_summary), onPermissions)
            SettingsItem.PRIVACY_POLICY ->
                row(section, title, stringResource(R.string.settings_privacy_policy_summary), onPrivacyPolicy)
            SettingsItem.LICENSES ->
                row(section, title, stringResource(R.string.settings_licenses_summary), onLicenses)
        }
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

/**
 * A row that runs a one-shot action rather than editing a preference — the
 * leading icon separates it visually from the plain [SettingRow]s around it.
 */
@Composable
private fun ActionRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    subtitle: String,
    onClick: () -> Unit,
) {
    ListItem(
        modifier = Modifier.clickable(onClick = onClick),
        leadingContent = { Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary) },
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

/**
 * Single-choice picker for the one-shot OTP cleanup. Unlike [RadioDialog]
 * nothing is pre-selected and nothing is persisted: the choice only feeds the
 * confirmation step that follows.
 */
@Composable
private fun ClearOtpDialog(
    onContinue: (ClearOtpRange) -> Unit,
    onDismiss: () -> Unit,
) {
    var choice by remember { mutableStateOf<ClearOtpRange?>(null) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.settings_clear_otp)) },
        text = {
            Column {
                Text(
                    text = stringResource(R.string.settings_clear_otp_note),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                ClearOtpRange.entries.forEach { range ->
                    Row(
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .heightIn(min = 48.dp)
                                .selectable(
                                    selected = choice == range,
                                    onClick = { choice = range },
                                    role = Role.RadioButton,
                                ).padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = choice == range, onClick = null)
                        Spacer(Modifier.padding(horizontal = 6.dp))
                        Text(clearOtpRangeLabel(range))
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = choice != null,
                onClick = { choice?.let(onContinue) },
            ) { Text(stringResource(R.string.action_continue)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
        },
    )
}

@Composable
private fun clearOtpRangeLabel(range: ClearOtpRange): String =
    when (range) {
        ClearOtpRange.ALL -> stringResource(R.string.clear_otp_all)
        ClearOtpRange.OLDER_THAN_1_DAY -> stringResource(R.string.clear_otp_1_day)
        ClearOtpRange.OLDER_THAN_3_DAYS -> stringResource(R.string.clear_otp_3_days)
        ClearOtpRange.OLDER_THAN_1_WEEK -> stringResource(R.string.clear_otp_1_week)
        ClearOtpRange.OLDER_THAN_2_WEEKS -> stringResource(R.string.clear_otp_2_weeks)
        ClearOtpRange.OLDER_THAN_1_MONTH -> stringResource(R.string.clear_otp_1_month)
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

/**
 * OTP size picker with a live preview of the digit size: exactly five
 * options, Option 1 (smallest) to Option 5 (largest). Option 2 is the
 * default — there is no separate "Default" entry.
 */
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
                                fontSize = otpPreviewFontSp(size).sp,
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
        NotificationAction.SHARE -> stringResource(R.string.notification_action_share)
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
private fun logoBackgroundLabel(value: LogoBackground): String =
    stringResource(
        when (value) {
            LogoBackground.WHITE -> R.string.logo_background_white
            LogoBackground.DARK -> R.string.logo_background_dark
            LogoBackground.DYNAMIC -> R.string.logo_background_dynamic
            LogoBackground.NONE -> R.string.logo_background_none
        },
    )

@Composable
private fun themeLabel(mode: ThemeMode): String =
    when (mode) {
        ThemeMode.SYSTEM -> stringResource(R.string.theme_system)
        ThemeMode.LIGHT -> stringResource(R.string.theme_light)
        ThemeMode.DARK -> stringResource(R.string.theme_dark)
    }

@Composable
private fun backupFrequencyLabel(frequency: BackupFrequency): String =
    when (frequency) {
        BackupFrequency.OFF -> stringResource(R.string.frequency_off)
        BackupFrequency.DAILY -> stringResource(R.string.frequency_daily)
        BackupFrequency.WEEKLY -> stringResource(R.string.frequency_weekly)
    }

@Composable
private fun otpDeleteLabel(policy: OtpAutoDeletePolicy): String =
    when (policy) {
        OtpAutoDeletePolicy.NEVER -> stringResource(R.string.otp_delete_never)
        OtpAutoDeletePolicy.HOURS_24 -> stringResource(R.string.otp_delete_24h)
        OtpAutoDeletePolicy.DAYS_3 -> stringResource(R.string.otp_delete_3d)
        OtpAutoDeletePolicy.DAYS_7 -> stringResource(R.string.otp_delete_7d)
        OtpAutoDeletePolicy.MONTH_1 -> stringResource(R.string.otp_delete_1m)
        OtpAutoDeletePolicy.MONTHS_3 -> stringResource(R.string.otp_delete_3m)
    }

@Composable
private fun otpSizeLabel(size: OtpDisplaySize): String =
    when (size) {
        OtpDisplaySize.OPTION_1 -> stringResource(R.string.otp_size_1)
        OtpDisplaySize.OPTION_2 -> stringResource(R.string.otp_size_2)
        OtpDisplaySize.OPTION_3 -> stringResource(R.string.otp_size_3)
        OtpDisplaySize.OPTION_4 -> stringResource(R.string.otp_size_4)
        OtpDisplaySize.OPTION_5 -> stringResource(R.string.otp_size_5)
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
