package app.clearsms.ui.inbox

import android.Manifest
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Archive
import androidx.compose.material.icons.outlined.Block
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Contacts
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Inbox
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.MarkEmailRead
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material.icons.outlined.SelectAll
import androidx.compose.material3.Badge
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeTopAppBar
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.paging.LoadState
import androidx.paging.compose.collectAsLazyPagingItems
import androidx.paging.compose.itemKey
import app.clearsms.R
import app.clearsms.domain.model.Category
import app.clearsms.domain.model.SwipeAction
import app.clearsms.sms.DefaultSmsAppHelper
import app.clearsms.ui.common.UndoUiEvent
import app.clearsms.ui.components.AvatarDefaults
import app.clearsms.ui.components.CategoryBadge
import app.clearsms.ui.components.DeleteConfirmationDialog
import app.clearsms.ui.components.EmptyState
import app.clearsms.ui.components.OtpBanner
import app.clearsms.ui.components.SelectionState
import app.clearsms.ui.components.SenderAvatar
import app.clearsms.ui.components.SwipeableMessageItem
import app.clearsms.ui.components.displayName
import app.clearsms.ui.navigation.SearchSettingsActions
import app.clearsms.ui.navigation.orderedPills
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.google.accompanist.permissions.isGranted
import com.google.accompanist.permissions.rememberPermissionState
import kotlinx.coroutines.launch

/** Main inbox: OTP banner, category filter chips and latest-per-sender threads. */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalPermissionsApi::class)
@Composable
fun InboxScreen(
    onOpenThread: (Long) -> Unit,
    onOpenMessage: (threadId: Long, messageId: Long) -> Unit,
    onCompose: () -> Unit,
    onSearch: () -> Unit,
    onSettings: () -> Unit,
    onCreateRule: (sender: String, body: String) -> Unit,
    viewModel: InboxViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val items = viewModel.pagedItems.collectAsLazyPagingItems()
    val selection by viewModel.selection.collectAsStateWithLifecycle()
    var confirmDelete by remember { mutableStateOf(false) }
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val otpCopiedMessage = stringResource(R.string.otp_copied)

    val contactsPermission = rememberPermissionState(Manifest.permission.READ_CONTACTS)
    var hadContactsPermission by remember { mutableStateOf(contactsPermission.status.isGranted) }
    LaunchedEffect(contactsPermission.status.isGranted) {
        val granted = contactsPermission.status.isGranted
        if (granted && !hadContactsPermission) viewModel.onContactsPermissionGranted()
        hadContactsPermission = granted
    }

    // Gmail-style transient undo: every delete/archive surfaces a snackbar
    // whose UNDO reverts the staged action (deletes commit to the system
    // provider only after the window closes - see UndoManager).
    val undoLabel = stringResource(R.string.undo_action)
    val resources = LocalContext.current.resources
    LaunchedEffect(Unit) {
        viewModel.undoEventFlow.collect { event ->
            val message =
                when (event) {
                    is UndoUiEvent.Deleted ->
                        resources.getQuantityString(R.plurals.undo_deleted, event.count, event.count)
                    is UndoUiEvent.Archived ->
                        resources.getQuantityString(R.plurals.undo_archived, event.count, event.count)
                }
            val result =
                snackbarHostState.showSnackbar(
                    message = message,
                    actionLabel = undoLabel,
                    duration = SnackbarDuration.Short,
                )
            if (result == SnackbarResult.ActionPerformed) viewModel.undo()
        }
    }

    // Losing the default-SMS role means new messages silently stop arriving.
    // Re-check on every resume so returning from the system role dialog (or
    // from another SMS app's settings) updates the banner live. Every check
    // also feeds the catch-up scheduler: regaining the role (or a cold-start
    // provider gap) imports the messages that landed while another app was
    // default.
    val context = LocalContext.current
    val defaultSmsBanner = remember { DefaultSmsBannerState() }
    val defaultSmsLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            val held = DefaultSmsAppHelper.isDefaultSmsApp(context)
            viewModel.onSmsRoleChecked(held, regained = defaultSmsBanner.onRoleChecked(held))
        }
    LifecycleResumeEffect(Unit) {
        val held = DefaultSmsAppHelper.isDefaultSmsApp(context)
        viewModel.onSmsRoleChecked(held, regained = defaultSmsBanner.onRoleChecked(held))
        onPauseOrDispose { }
    }

    // System back exits selection mode instead of leaving the screen.
    BackHandler(enabled = selection.active) { viewModel.exitSelection() }

    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            if (selection.active) {
                InboxSelectionBar(
                    selection = selection,
                    // Single-item-only actions from the old long-press sheet
                    // live in the overflow when exactly one thread is selected.
                    singleItem =
                        if (selection.count == 1) {
                            items.itemSnapshotList.items.firstOrNull {
                                it.message.threadId == selection.selected.first()
                            }
                        } else {
                            null
                        },
                    onClose = viewModel::exitSelection,
                    onDelete = { confirmDelete = true },
                    onArchive = viewModel::archiveSelected,
                    onToggleRead = viewModel::toggleReadSelected,
                    onTogglePin = viewModel::togglePinSelected,
                    onSelectAll = viewModel::selectAll,
                    onBlock = { sender ->
                        viewModel.block(sender)
                        viewModel.exitSelection()
                    },
                    onChangeCategory = { sender, body ->
                        viewModel.exitSelection()
                        onCreateRule(sender, body)
                    },
                )
            } else {
                LargeTopAppBar(
                    title = { Text(stringResource(R.string.inbox_title)) },
                    actions = { SearchSettingsActions(onSearch = onSearch, onSettings = onSettings) },
                    scrollBehavior = scrollBehavior,
                )
            }
        },
        floatingActionButton = {
            if (!selection.active) {
                FloatingActionButton(onClick = onCompose) {
                    Icon(Icons.Outlined.Edit, contentDescription = stringResource(R.string.action_compose))
                }
            }
        },
    ) { padding ->
        // Deliberately no pull-to-refresh: the gesture triggered a full
        // recategorization inline, which hung the UI on large inboxes.
        // Re-sorting is available via Settings → Sort inbox again, which
        // runs in a WorkManager worker with progress.
        Box(modifier = Modifier.fillMaxSize().padding(padding)) {
            val emptyLoaded = items.loadState.refresh is LoadState.NotLoading && items.itemCount == 0
            if (emptyLoaded && state.filter == InboxFilterState()) {
                EmptyState(
                    icon = Icons.Outlined.Inbox,
                    title = stringResource(R.string.inbox_empty_title),
                    subtitle = stringResource(R.string.inbox_empty_subtitle),
                )
            } else {
                LazyColumn(modifier = Modifier.fillMaxSize()) {
                    if (defaultSmsBanner.visible) {
                        item(key = "default_sms_banner") {
                            DefaultSmsBanner(
                                onSetDefault = {
                                    defaultSmsLauncher.launch(DefaultSmsAppHelper.createRequestIntent(context))
                                },
                                onDismiss = defaultSmsBanner::dismiss,
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                            )
                        }
                    }
                    if (!contactsPermission.status.isGranted) {
                        item(key = "contacts_permission") {
                            ContactsPermissionBanner(
                                onGrant = { contactsPermission.launchPermissionRequest() },
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                            )
                        }
                    }
                    state.latestOtp?.let { otp ->
                        item(key = "otp_banner") {
                            OtpBanner(
                                code = otp.code,
                                senderName = otp.senderName,
                                displaySize = state.otpDisplaySize,
                                onCopied = {
                                    viewModel.markOtpHandled(otp.messageId)
                                    scope.launch { snackbarHostState.showSnackbar(otpCopiedMessage) }
                                },
                                onDismiss = { viewModel.markOtpHandled(otp.messageId) },
                                onOpenMessage = { onOpenMessage(otp.threadId, otp.messageId) },
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                            )
                        }
                    }
                    item(key = "filters") {
                        FilterChipRow(
                            filter = state.filter,
                            unreadCounts = state.unreadCounts,
                            totalUnread = state.totalUnread,
                            pillOrder = state.pillOrder,
                            onSelectCategory = viewModel::selectCategory,
                            onToggleUnread = viewModel::toggleUnread,
                        )
                    }
                    if (emptyLoaded) {
                        item(key = "empty_filter") {
                            Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
                                Text(
                                    text = stringResource(R.string.inbox_empty_filtered),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                    items(
                        count = items.itemCount,
                        key = items.itemKey { it.message.id },
                    ) { index ->
                        val item = items[index] ?: return@items
                        val threadId = item.message.threadId
                        val selected = selection.isSelected(threadId)
                        SwipeableMessageItem(
                            // Swipes are disabled entirely while selecting.
                            startAction = if (selection.active) SwipeAction.NONE else state.swipeStart,
                            endAction = if (selection.active) SwipeAction.NONE else state.swipeEnd,
                            onAction = { action ->
                                when (action) {
                                    SwipeAction.ARCHIVE -> viewModel.archive(item.message.id)
                                    SwipeAction.DELETE -> viewModel.delete(item.message.id)
                                    SwipeAction.TOGGLE_READ ->
                                        viewModel.markRead(item.message.id, read = !item.message.isRead)
                                    SwipeAction.NONE -> Unit
                                }
                            },
                        ) {
                            InboxRow(
                                item = item,
                                richAvatars = state.richAvatars,
                                showCategoryTag = state.filter.showsCategoryTags,
                                selected = selected,
                                onClick = {
                                    if (selection.active) {
                                        viewModel.toggleSelection(threadId)
                                    } else {
                                        viewModel.markRead(item.message.id, read = true)
                                        onOpenThread(threadId)
                                    }
                                },
                                onLongClick = { viewModel.enterSelection(threadId) },
                            )
                        }
                    }
                }
            }
        }
    }

    if (confirmDelete) {
        DeleteConfirmationDialog(
            title = stringResource(R.string.selection_delete_threads_title),
            text = stringResource(R.string.selection_delete_threads_message, selection.count),
            onConfirm = {
                confirmDelete = false
                viewModel.deleteSelected()
            },
            onDismiss = { confirmDelete = false },
        )
    }
}

/** Contextual top bar shown while thread multi-select is active. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun InboxSelectionBar(
    selection: SelectionState<Long>,
    singleItem: InboxItem?,
    onClose: () -> Unit,
    onDelete: () -> Unit,
    onArchive: () -> Unit,
    onToggleRead: () -> Unit,
    onTogglePin: () -> Unit,
    onSelectAll: () -> Unit,
    onBlock: (sender: String) -> Unit,
    onChangeCategory: (sender: String, body: String) -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }
    TopAppBar(
        title = { Text(stringResource(R.string.selection_count, selection.count)) },
        navigationIcon = {
            IconButton(onClick = onClose) {
                Icon(Icons.Outlined.Close, contentDescription = stringResource(R.string.action_exit_selection))
            }
        },
        actions = {
            IconButton(onClick = onDelete) {
                Icon(Icons.Outlined.Delete, contentDescription = stringResource(R.string.ui_action_delete))
            }
            IconButton(onClick = onArchive) {
                Icon(Icons.Outlined.Archive, contentDescription = stringResource(R.string.action_archive))
            }
            IconButton(onClick = onToggleRead) {
                Icon(
                    Icons.Outlined.MarkEmailRead,
                    contentDescription = stringResource(R.string.action_mark_read_unread),
                )
            }
            // Pin/Unpin: pins when anything selected is unpinned, unpins
            // when everything already is. Multiple pins are allowed.
            IconButton(onClick = onTogglePin) {
                Icon(
                    Icons.Outlined.PushPin,
                    contentDescription = stringResource(R.string.action_pin_unpin),
                )
            }
            IconButton(onClick = onSelectAll) {
                Icon(Icons.Outlined.SelectAll, contentDescription = stringResource(R.string.action_select_all))
            }
            if (singleItem != null) {
                IconButton(onClick = { menuOpen = true }) {
                    Icon(Icons.Outlined.MoreVert, contentDescription = stringResource(R.string.action_more_options))
                }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.action_block_sender)) },
                        leadingIcon = { Icon(Icons.Outlined.Block, contentDescription = null) },
                        onClick = {
                            menuOpen = false
                            onBlock(singleItem.message.sender)
                        },
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.action_change_category)) },
                        leadingIcon = { Icon(Icons.Outlined.Edit, contentDescription = null) },
                        onClick = {
                            menuOpen = false
                            onChangeCategory(singleItem.message.sender, singleItem.message.body)
                        },
                    )
                }
            }
        },
    )
}

/**
 * Persistent warning shown while Clear SMS is not the default SMS app:
 * without the role, new incoming messages never reach the app. Inline (not
 * a snackbar), dismissible for the session only.
 */
@Composable
private fun DefaultSmsBanner(
    onSetDefault: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.errorContainer,
        modifier = modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(start = 16.dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                Icons.Outlined.Info,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onErrorContainer,
            )
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.inbox_default_sms_banner),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                )
                TextButton(onClick = onSetDefault) {
                    Text(stringResource(R.string.onboarding_set_default))
                }
            }
            IconButton(onClick = onDismiss) {
                Icon(
                    Icons.Outlined.Close,
                    contentDescription = stringResource(R.string.inbox_default_sms_dismiss),
                    tint = MaterialTheme.colorScheme.onErrorContainer,
                )
            }
        }
    }
}

/** Compact prompt shown while READ_CONTACTS is missing. */
@Composable
private fun ContactsPermissionBanner(
    onGrant: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.secondaryContainer,
        modifier = modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                Icons.Outlined.Contacts,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSecondaryContainer,
            )
            Spacer(Modifier.width(12.dp))
            Text(
                text = stringResource(R.string.inbox_contacts_permission),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = onGrant) {
                Text(stringResource(R.string.inbox_contacts_grant))
            }
        }
    }
}

@Composable
private fun FilterChipRow(
    filter: InboxFilterState,
    unreadCounts: Map<Category, Int>,
    totalUnread: Int,
    pillOrder: List<Category>,
    onSelectCategory: (Category) -> Unit,
    onToggleUnread: () -> Unit,
) {
    LazyRow(
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        // "Unread" is an independent toggle that composes with any category.
        item(key = "unread") {
            FilterChip(
                selected = filter.unreadOnly,
                onClick = onToggleUnread,
                label = { Text(stringResource(R.string.filter_unread)) },
                trailingIcon =
                    if (totalUnread > 0) {
                        { Badge { Text(totalUnread.toString()) } }
                    } else {
                        null
                    },
            )
        }
        items(orderedPills(pillOrder, Category.entries.toList()), key = { it.name }) { category ->
            val count = unreadCounts[category] ?: 0
            FilterChip(
                selected = filter.category == category,
                onClick = { onSelectCategory(category) },
                label = { Text(category.displayName()) },
                trailingIcon =
                    if (count > 0) {
                        { Badge { Text(count.toString()) } }
                    } else {
                        null
                    },
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun InboxRow(
    item: InboxItem,
    richAvatars: Boolean,
    showCategoryTag: Boolean,
    selected: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    val message = item.message
    val unread = !message.isRead
    ListItem(
        modifier =
            Modifier.combinedClickable(
                onClick = onClick,
                onLongClick = onLongClick,
                onLongClickLabel = stringResource(R.string.inbox_row_actions),
            ),
        colors =
            ListItemDefaults.colors(
                containerColor =
                    if (selected) {
                        MaterialTheme.colorScheme.secondaryContainer
                    } else {
                        MaterialTheme.colorScheme.surface
                    },
            ),
        leadingContent = {
            if (selected) {
                SelectedCheckAvatar()
            } else {
                SenderAvatar(
                    name = item.display.name,
                    richAvatars = richAvatars,
                    photoUri = item.display.photoUri,
                    isKnownSender = item.display.isKnownSender,
                    glyph = item.glyph,
                )
            }
        },
        headlineContent = {
            Text(
                text = item.display.name,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = if (unread) FontWeight.Bold else FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        },
        supportingContent = {
            Column {
                val draft = item.draftText
                if (draft != null) {
                    // Draft preview replaces the last-message snippet (mail-app
                    // convention); the tertiary tone marks it as YOUR unsent
                    // text, not an incoming message. Presence never bolds the
                    // row or moves it - unread/sort come from messages only.
                    Text(
                        text = stringResource(R.string.inbox_draft_preview, draft),
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        color = MaterialTheme.colorScheme.tertiary,
                    )
                } else {
                    Text(
                        text = message.body,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = if (unread) FontWeight.SemiBold else FontWeight.Normal,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (showCategoryTag) {
                    Spacer(Modifier.height(4.dp))
                    CategoryBadge(category = message.category)
                }
            }
        },
        trailingContent = {
            Column(horizontalAlignment = Alignment.End) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (item.pinned) {
                        Icon(
                            Icons.Outlined.PushPin,
                            contentDescription = stringResource(R.string.inbox_pinned),
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(14.dp),
                        )
                        Spacer(Modifier.width(4.dp))
                    }
                    Text(
                        text = item.timeLabel,
                        style = MaterialTheme.typography.labelMedium,
                        color =
                            if (unread) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                    )
                }
                if (unread) {
                    Spacer(Modifier.height(6.dp))
                    Surface(
                        modifier = Modifier.size(10.dp),
                        shape = CircleShape,
                        color = MaterialTheme.colorScheme.primary,
                    ) {}
                }
            }
        },
    )
}

/** Check-mark avatar replacing the sender avatar on selected rows. */
@Composable
private fun SelectedCheckAvatar() {
    Surface(
        modifier = Modifier.size(AvatarDefaults.size),
        shape = AvatarDefaults.shape,
        color = MaterialTheme.colorScheme.primary,
    ) {
        Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
            Icon(
                Icons.Outlined.Check,
                contentDescription = stringResource(R.string.selection_selected),
                tint = MaterialTheme.colorScheme.onPrimary,
            )
        }
    }
}
