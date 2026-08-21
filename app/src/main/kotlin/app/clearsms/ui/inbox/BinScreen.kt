package app.clearsms.ui.inbox

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.DeleteForever
import androidx.compose.material.icons.outlined.DeleteSweep
import androidx.compose.material.icons.outlined.RestoreFromTrash
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.clearsms.R
import app.clearsms.ui.components.CategoryBadge
import app.clearsms.ui.components.EmptyState
import app.clearsms.ui.components.SenderAvatar
import app.clearsms.ui.components.SwipeDismissSnackbarHost

/**
 * Recycle bin: deleted messages resting for 30 days. Reached from
 * Settings → Messages → Recycle bin. Rows restore back to the inbox (and
 * the system provider when possible) or are removed forever after a
 * confirmation; the top bar empties the whole bin.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BinScreen(
    onBack: () -> Unit,
    viewModel: BinViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    var confirmDeleteForever by remember { mutableStateOf<Long?>(null) }
    var confirmEmptyBin by remember { mutableStateOf(false) }

    val restoredMessage = stringResource(R.string.bin_restored)
    val restoredAppOnlyMessage = stringResource(R.string.bin_restored_app_only)
    LaunchedEffect(Unit) {
        viewModel.events.collect { event ->
            snackbarHostState.showSnackbar(
                when (event) {
                    BinEvent.Restored -> restoredMessage
                    BinEvent.RestoredAppOnly -> restoredAppOnlyMessage
                },
            )
        }
    }

    Scaffold(
        snackbarHost = { SwipeDismissSnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.bin_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Outlined.ArrowBack,
                            contentDescription = stringResource(R.string.action_back),
                        )
                    }
                },
                actions = {
                    if (state.items.isNotEmpty()) {
                        IconButton(onClick = { confirmEmptyBin = true }) {
                            Icon(
                                Icons.Outlined.DeleteSweep,
                                contentDescription = stringResource(R.string.bin_empty_bin),
                            )
                        }
                    }
                },
            )
        },
    ) { padding ->
        if (state.loaded && state.items.isEmpty()) {
            EmptyState(
                icon = Icons.Outlined.RestoreFromTrash,
                title = stringResource(R.string.bin_empty_title),
                subtitle = stringResource(R.string.bin_empty_subtitle),
                modifier = Modifier.padding(padding),
            )
            return@Scaffold
        }
        LazyColumn(modifier = Modifier.fillMaxSize().padding(padding)) {
            items(state.items, key = { it.message.id }) { item ->
                BinRow(
                    item = item,
                    richAvatars = state.richAvatars,
                    onRestore = { viewModel.restore(item.message.id) },
                    onDeleteForever = { confirmDeleteForever = item.message.id },
                )
            }
        }
    }

    confirmDeleteForever?.let { messageId ->
        AlertDialog(
            onDismissRequest = { confirmDeleteForever = null },
            title = { Text(stringResource(R.string.bin_delete_forever_title)) },
            text = { Text(stringResource(R.string.bin_delete_forever_message)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmDeleteForever = null
                        viewModel.deleteForever(messageId)
                    },
                ) { Text(stringResource(R.string.bin_delete_forever)) }
            },
            dismissButton = {
                TextButton(onClick = { confirmDeleteForever = null }) {
                    Text(stringResource(R.string.action_cancel))
                }
            },
        )
    }
    if (confirmEmptyBin) {
        AlertDialog(
            onDismissRequest = { confirmEmptyBin = false },
            title = { Text(stringResource(R.string.bin_empty_confirm_title)) },
            text = { Text(stringResource(R.string.bin_empty_confirm_message, state.items.size)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmEmptyBin = false
                        viewModel.emptyBin()
                    },
                ) { Text(stringResource(R.string.bin_empty_bin)) }
            },
            dismissButton = {
                TextButton(onClick = { confirmEmptyBin = false }) {
                    Text(stringResource(R.string.action_cancel))
                }
            },
        )
    }
}

@Composable
private fun BinRow(
    item: InboxItem,
    richAvatars: Boolean,
    onRestore: () -> Unit,
    onDeleteForever: () -> Unit,
) {
    val message = item.message
    ListItem(
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
                IconButton(onClick = onRestore) {
                    Icon(
                        Icons.Outlined.RestoreFromTrash,
                        contentDescription = stringResource(R.string.bin_restore),
                    )
                }
                IconButton(onClick = onDeleteForever) {
                    Icon(
                        Icons.Outlined.DeleteForever,
                        contentDescription = stringResource(R.string.bin_delete_forever),
                    )
                }
            }
        },
    )
}
