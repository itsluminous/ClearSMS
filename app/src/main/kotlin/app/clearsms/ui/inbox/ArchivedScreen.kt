package app.clearsms.ui.inbox

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Archive
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.SelectAll
import androidx.compose.material.icons.outlined.Unarchive
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.clearsms.R
import app.clearsms.ui.common.UndoUiEvent
import app.clearsms.ui.components.AvatarDefaults
import app.clearsms.ui.components.CategoryBadge
import app.clearsms.ui.components.DeleteConfirmationDialog
import app.clearsms.ui.components.EmptyState
import app.clearsms.ui.components.SenderAvatar

/**
 * Archived conversations, with per-row and multi-select unarchive / delete.
 * Reached from Settings — archived threads never appear in
 * the inbox or its unread counts.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ArchivedScreen(
    onOpenThread: (Long) -> Unit,
    onBack: () -> Unit,
    viewModel: ArchivedViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val selection by viewModel.selection.collectAsStateWithLifecycle()
    var confirmDelete by remember { mutableStateOf(false) }
    var confirmDeleteRow by remember { mutableStateOf<InboxItem?>(null) }
    val snackbarHostState = remember { SnackbarHostState() }

    // Deletes are undoable: the snackbar's UNDO reverts the staged action
    // before its deferred provider commit (see UndoManager).
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

    BackHandler(enabled = selection.active) { viewModel.exitSelection() }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            if (selection.active) {
                TopAppBar(
                    title = { Text(stringResource(R.string.selection_count, selection.count)) },
                    navigationIcon = {
                        IconButton(onClick = viewModel::exitSelection) {
                            Icon(
                                Icons.Outlined.Close,
                                contentDescription = stringResource(R.string.action_exit_selection),
                            )
                        }
                    },
                    actions = {
                        IconButton(onClick = viewModel::unarchiveSelected) {
                            Icon(
                                Icons.Outlined.Unarchive,
                                contentDescription = stringResource(R.string.action_unarchive),
                            )
                        }
                        IconButton(onClick = { confirmDelete = true }) {
                            Icon(
                                Icons.Outlined.Delete,
                                contentDescription = stringResource(R.string.ui_action_delete),
                            )
                        }
                        IconButton(onClick = viewModel::selectAll) {
                            Icon(
                                Icons.Outlined.SelectAll,
                                contentDescription = stringResource(R.string.action_select_all),
                            )
                        }
                    },
                )
            } else {
                TopAppBar(
                    title = { Text(stringResource(R.string.archived_title)) },
                    navigationIcon = {
                        IconButton(onClick = onBack) {
                            Icon(
                                Icons.AutoMirrored.Outlined.ArrowBack,
                                contentDescription = stringResource(R.string.action_back),
                            )
                        }
                    },
                )
            }
        },
    ) { padding ->
        if (state.loaded && state.items.isEmpty()) {
            EmptyState(
                icon = Icons.Outlined.Archive,
                title = stringResource(R.string.archived_empty_title),
                subtitle = stringResource(R.string.archived_empty_subtitle),
                modifier = Modifier.padding(padding),
            )
            return@Scaffold
        }
        LazyColumn(modifier = Modifier.fillMaxSize().padding(padding)) {
            items(state.items, key = { it.message.id }) { item ->
                val threadId = item.message.threadId
                ArchivedRow(
                    item = item,
                    richAvatars = state.richAvatars,
                    selected = selection.isSelected(threadId),
                    onClick = {
                        if (selection.active) {
                            viewModel.toggleSelection(threadId)
                        } else {
                            onOpenThread(threadId)
                        }
                    },
                    onLongClick = { viewModel.enterSelection(threadId) },
                    onUnarchive = { viewModel.unarchive(threadId) },
                    onDelete = { confirmDeleteRow = item },
                )
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
    confirmDeleteRow?.let { item ->
        DeleteConfirmationDialog(
            title = stringResource(R.string.selection_delete_threads_title),
            text = stringResource(R.string.selection_delete_threads_message, 1),
            onConfirm = {
                confirmDeleteRow = null
                viewModel.delete(item.message.threadId)
            },
            onDismiss = { confirmDeleteRow = null },
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ArchivedRow(
    item: InboxItem,
    richAvatars: Boolean,
    selected: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onUnarchive: () -> Unit,
    onDelete: () -> Unit,
) {
    val message = item.message
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
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        },
        supportingContent = {
            Column {
                Text(
                    text = message.body,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(4.dp))
                CategoryBadge(category = message.category)
            }
        },
        trailingContent = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(horizontalAlignment = Alignment.End) {
                    Text(
                        text = item.timeLabel,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                IconButton(onClick = onUnarchive) {
                    Icon(
                        Icons.Outlined.Unarchive,
                        contentDescription = stringResource(R.string.action_unarchive),
                    )
                }
                IconButton(onClick = onDelete) {
                    Icon(
                        Icons.Outlined.Delete,
                        contentDescription = stringResource(R.string.ui_action_delete),
                    )
                }
            }
        },
    )
}
