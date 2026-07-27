package app.clearsms.ui.inbox

import android.Manifest
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.outlined.Contacts
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Inbox
import androidx.compose.material.icons.outlined.MarkEmailRead
import androidx.compose.material.icons.outlined.MarkEmailUnread
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.Badge
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeTopAppBar
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.clearsms.R
import app.clearsms.domain.model.Category
import app.clearsms.domain.model.SwipeAction
import app.clearsms.ui.common.RelativeTime
import app.clearsms.ui.components.CategoryBadge
import app.clearsms.ui.components.EmptyState
import app.clearsms.ui.components.OtpBanner
import app.clearsms.ui.components.SenderAvatar
import app.clearsms.ui.components.SwipeableMessageItem
import app.clearsms.ui.components.displayName
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.google.accompanist.permissions.isGranted
import com.google.accompanist.permissions.rememberPermissionState

/** Main inbox: OTP banner, category filter chips and latest-per-sender threads. */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalPermissionsApi::class)
@Composable
fun InboxScreen(
    onOpenThread: (Long) -> Unit,
    onCompose: () -> Unit,
    onSearch: () -> Unit,
    onSettings: () -> Unit,
    onCreateRule: (sender: String, body: String) -> Unit,
    viewModel: InboxViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    var sheetItem by remember { mutableStateOf<InboxItem?>(null) }
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()

    val contactsPermission = rememberPermissionState(Manifest.permission.READ_CONTACTS)
    var hadContactsPermission by remember { mutableStateOf(contactsPermission.status.isGranted) }
    LaunchedEffect(contactsPermission.status.isGranted) {
        val granted = contactsPermission.status.isGranted
        if (granted && !hadContactsPermission) viewModel.onContactsPermissionGranted()
        hadContactsPermission = granted
    }

    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            LargeTopAppBar(
                title = { Text(stringResource(R.string.inbox_title)) },
                actions = {
                    IconButton(onClick = onSearch) {
                        Icon(Icons.Outlined.Search, contentDescription = stringResource(R.string.action_search))
                    }
                    IconButton(onClick = onSettings) {
                        Icon(Icons.Outlined.Settings, contentDescription = stringResource(R.string.action_settings))
                    }
                },
                scrollBehavior = scrollBehavior,
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = onCompose) {
                Icon(Icons.Outlined.Edit, contentDescription = stringResource(R.string.action_compose))
            }
        },
    ) { padding ->
        PullToRefreshBox(
            isRefreshing = state.isRefreshing,
            onRefresh = viewModel::refresh,
            modifier = Modifier.fillMaxSize().padding(padding),
        ) {
            if (state.loaded && state.items.isEmpty() && state.filter == InboxFilterState()) {
                EmptyState(
                    icon = Icons.Outlined.Inbox,
                    title = stringResource(R.string.inbox_empty_title),
                    subtitle = stringResource(R.string.inbox_empty_subtitle),
                )
            } else {
                LazyColumn(modifier = Modifier.fillMaxSize()) {
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
                                onCopied = {},
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                            )
                        }
                    }
                    item(key = "filters") {
                        FilterChipRow(
                            filter = state.filter,
                            unreadCounts = state.unreadCounts,
                            totalUnread = state.totalUnread,
                            onSelectCategory = viewModel::selectCategory,
                            onToggleUnread = viewModel::toggleUnread,
                        )
                    }
                    if (state.items.isEmpty()) {
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
                    items(state.items, key = { it.message.id }) { item ->
                        SwipeableMessageItem(
                            startAction = state.swipeStart,
                            endAction = state.swipeEnd,
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
                                onClick = {
                                    viewModel.markRead(item.message.id, read = true)
                                    onOpenThread(item.message.threadId)
                                },
                                onLongClick = { sheetItem = item },
                            )
                        }
                    }
                }
            }
        }
    }

    sheetItem?.let { item ->
        MessageActionsSheet(
            item = item,
            onDismiss = { sheetItem = null },
            onMarkRead = { read ->
                viewModel.markRead(item.message.id, read)
                sheetItem = null
            },
            onArchive = {
                viewModel.archive(item.message.id)
                sheetItem = null
            },
            onDelete = {
                viewModel.delete(item.message.id)
                sheetItem = null
            },
            onBlock = {
                viewModel.block(item.message.sender)
                sheetItem = null
            },
            onChangeCategory = {
                sheetItem = null
                onCreateRule(item.message.sender, item.message.body)
            },
        )
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
        items(Category.entries.toList(), key = { it.name }) { category ->
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
        colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surface),
        leadingContent = {
            SenderAvatar(
                name = item.display.name,
                richAvatars = richAvatars,
                photoUri = item.display.photoUri,
                isKnownSender = item.display.isKnownSender,
                glyph = item.glyph,
            )
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
                Text(
                    text = message.body,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = if (unread) FontWeight.SemiBold else FontWeight.Normal,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(4.dp))
                CategoryBadge(category = message.category)
            }
        },
        trailingContent = {
            Column(horizontalAlignment = Alignment.End) {
                Text(
                    text = RelativeTime.format(message.timestamp),
                    style = MaterialTheme.typography.labelMedium,
                    color =
                        if (unread) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                )
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MessageActionsSheet(
    item: InboxItem,
    onDismiss: () -> Unit,
    onMarkRead: (Boolean) -> Unit,
    onArchive: () -> Unit,
    onDelete: () -> Unit,
    onBlock: () -> Unit,
    onChangeCategory: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(modifier = Modifier.padding(bottom = 24.dp)) {
            Text(
                text = item.display.name,
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
            )
            if (item.message.isRead) {
                SheetAction(Icons.Outlined.MarkEmailUnread, stringResource(R.string.action_mark_unread)) { onMarkRead(false) }
            } else {
                SheetAction(Icons.Outlined.MarkEmailRead, stringResource(R.string.action_mark_read)) { onMarkRead(true) }
            }
            SheetAction(Icons.Outlined.Archive, stringResource(R.string.action_archive), onArchive)
            SheetAction(Icons.Outlined.Delete, stringResource(R.string.ui_action_delete), onDelete)
            SheetAction(Icons.Outlined.Block, stringResource(R.string.action_block_sender), onBlock)
            SheetAction(Icons.Outlined.Edit, stringResource(R.string.action_change_category), onChangeCategory)
        }
    }
}

@Composable
private fun SheetAction(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
) {
    ListItem(
        modifier = Modifier.clickable(onClick = onClick),
        leadingContent = { Icon(icon, contentDescription = null) },
        headlineContent = { Text(label) },
    )
}
