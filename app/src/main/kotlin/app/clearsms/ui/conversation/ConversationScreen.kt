package app.clearsms.ui.conversation

import android.text.format.DateFormat
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.AddCircleOutline
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.Password
import androidx.compose.material.icons.outlined.SelectAll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.paging.compose.collectAsLazyPagingItems
import androidx.paging.compose.itemKey
import app.clearsms.R
import app.clearsms.data.db.DeliveryStatus
import app.clearsms.ui.common.RelativeTime
import app.clearsms.ui.common.UndoUiEvent
import app.clearsms.ui.components.AmountKind
import app.clearsms.ui.components.AmountText
import app.clearsms.ui.components.MessageComposerBar
import app.clearsms.ui.components.ScheduleTimePicker
import app.clearsms.ui.components.SelectionState
import app.clearsms.ui.components.SenderAvatar
import app.clearsms.ui.components.amountKindOf
import kotlinx.coroutines.delay

/** How long the opened-message highlight stays fully visible... */
private const val HIGHLIGHT_HOLD_MS = 1_600

/** ...and how long it then takes to fade out. */
private const val HIGHLIGHT_FADE_MS = 600

/** Conversation thread: chat bubbles, date separators, parsed-detail cards and reply. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConversationScreen(
    onBack: () -> Unit,
    onCreateRule: (sender: String, body: String) -> Unit,
    viewModel: ConversationViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val simState by viewModel.simState.collectAsStateWithLifecycle()
    val items = viewModel.pagedItems.collectAsLazyPagingItems()
    val selection by viewModel.selection.collectAsStateWithLifecycle()
    val clipboard = LocalClipboardManager.current
    // Draft lives in the ViewModel (persisted per thread): leaving and
    // reopening the conversation restores unsent text.
    val draft by viewModel.draft.collectAsStateWithLifecycle()
    var confirmDelete by remember { mutableStateOf(false) }

    // Failed outgoing message whose Retry/Delete dialog is open (from a
    // bubble tap); survives rotation so the choice is never silently lost.
    var failedMessageId by rememberSaveable { mutableStateOf<Long?>(null) }

    // Scheduled bubble whose Send now / Edit time / Cancel dialog is open.
    var scheduledMessageId by rememberSaveable { mutableStateOf<Long?>(null) }

    // Date+time picker state: open flag plus the schedule being edited
    // (null = scheduling the current draft from a Send long-press).
    var showSchedulePicker by rememberSaveable { mutableStateOf(false) }
    var scheduleEditTarget by rememberSaveable { mutableStateOf<Long?>(null) }
    val listState = rememberLazyListState()
    val snackbarHostState = remember { SnackbarHostState() }

    // Send outcomes surface as snackbars; a failure offers a Retry action.
    val sentMessage = stringResource(R.string.message_sent)
    val notSentMessage = stringResource(R.string.message_not_sent)
    val retryLabel = stringResource(R.string.action_retry)
    LaunchedEffect(Unit) {
        viewModel.events.collect { event ->
            when (event) {
                SendEvent.Sent -> snackbarHostState.showSnackbar(sentMessage)
                is SendEvent.Failed -> {
                    val result =
                        snackbarHostState.showSnackbar(
                            message = notSentMessage,
                            actionLabel = retryLabel,
                            duration = SnackbarDuration.Long,
                        )
                    if (result == SnackbarResult.ActionPerformed) viewModel.retry(event.messageId)
                }
            }
        }
    }

    // One-time discoverability nudge: the first send from any compose bar
    // (per install) points at the invisible long-press-to-schedule.
    val scheduleTip = stringResource(R.string.conversation_schedule_tip)
    LaunchedEffect(Unit) {
        viewModel.scheduleTipFlow.collect { snackbarHostState.showSnackbar(scheduleTip) }
    }

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

    // A message id arriving via navigation (search result, Alerts/Finance
    // card or notification tap) is scrolled to and briefly highlighted. The
    // state machine keeps waiting across page loads until the target is in
    // the loaded window - the target id is read straight from the ViewModel
    // (NOT from the async uiState, which raced the first page load and
    // silently dropped the highlight).
    val highlight = remember { MessageHighlightState(viewModel.highlightTarget) }
    var highlightedItemId by remember { mutableStateOf<Long?>(null) }
    LaunchedEffect(items.itemCount) {
        if (items.itemCount == 0) return@LaunchedEffect
        val index = highlight.onItemsLoaded(items.itemSnapshotList.items.map { it.id })
        if (index != null) {
            listState.scrollToItem(index)
            highlightedItemId = viewModel.highlightTarget
        }
    }
    // Hold the highlight fully visible, then let the bubble fade it out.
    LaunchedEffect(highlightedItemId) {
        if (highlightedItemId != null) {
            delay(HIGHLIGHT_HOLD_MS.toLong())
            highlightedItemId = null
            delay(HIGHLIGHT_FADE_MS.toLong())
            highlight.onHighlightFinished()
        }
    }

    // Sending a reply pins the list back to the bottom once the row lands.
    LaunchedEffect(Unit) {
        viewModel.scrollToBottom.collect { listState.animateScrollToItem(0) }
    }

    // Tap-to-reveal metadata: at most ONE message is expanded at a time
    // (see MessageMetadata.onTap); survives rotation.
    var expandedId by rememberSaveable { mutableStateOf<Long?>(null) }

    BackHandler(enabled = selection.active) { viewModel.exitSelection() }

    Scaffold(
        topBar = {
            if (selection.active) {
                ConversationSelectionBar(
                    selection = selection,
                    singleItem =
                        if (selection.count == 1) {
                            items.itemSnapshotList.items.firstOrNull { it.id == selection.selected.first() }
                        } else {
                            null
                        },
                    onClose = viewModel::exitSelection,
                    onDelete = { confirmDelete = true },
                    onCopy = {
                        viewModel.copySelected { text -> clipboard.setText(AnnotatedString(text)) }
                    },
                    onSelectAll = viewModel::selectAll,
                    onCopyOtp = { otp -> clipboard.setText(AnnotatedString(otp)) },
                    onCreateRule = { body ->
                        viewModel.exitSelection()
                        onCreateRule(state.address, body)
                    },
                )
            } else {
                TopAppBar(
                    title = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            SenderAvatar(
                                name = state.title,
                                richAvatars = state.richAvatars,
                                photoUri = state.photoUri,
                                isKnownSender = state.isKnownSender,
                                glyph = state.glyph,
                            )
                            Spacer(Modifier.width(12.dp))
                            Text(
                                text = state.title,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    },
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
        snackbarHost = { SnackbarHost(snackbarHostState) },
        bottomBar = {
            when {
                !state.loaded -> Unit
                state.repliable ->
                    MessageComposerBar(
                        draft = draft,
                        onDraftChange = viewModel::setDraft,
                        onSend = {
                            // Optimistic: the ViewModel clears the field (and
                            // the saved draft) immediately; the bubble tracks
                            // the send state.
                            viewModel.send(draft)
                        },
                        sim = simState,
                        onCycleSim = {
                            viewModel.cycleSim()
                        },
                        onScheduleSend = {
                            scheduleEditTarget = null
                            showSchedulePicker = true
                        },
                    )
                else -> NotRepliableRow()
            }
        },
    ) { padding ->
        LazyColumn(
            state = listState,
            reverseLayout = true,
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(vertical = 8.dp),
        ) {
            items(
                count = items.itemCount,
                key = items.itemKey { it.id },
            ) { index ->
                val item = items[index] ?: return@items
                // The next index holds the chronologically OLDER message; a
                // date separator belongs above the first message of each day.
                val older = if (index + 1 < items.itemCount) items.peek(index + 1) else null
                val showSeparator =
                    if (older != null) {
                        !RelativeTime.sameDay(older.timestamp, item.timestamp)
                    } else {
                        items.loadState.append.endOfPaginationReached
                    }
                Column {
                    if (showSeparator) DateSeparator(item.timestamp)
                    MessageBubble(
                        item = item,
                        highlighted = item.id == highlightedItemId,
                        selected = selection.isSelected(item.id),
                        showDetails = state.showTransactionDetails,
                        expanded = expandedId == item.id,
                        onClick = {
                            when (
                                MessageMetadata.tapAction(
                                    selectionActive = selection.active,
                                    outgoing = item.outgoing,
                                    deliveryStatus = item.deliveryStatus,
                                )
                            ) {
                                MessageMetadata.TapAction.TOGGLE_SELECTION -> viewModel.toggleSelection(item.id)
                                MessageMetadata.TapAction.OFFER_RETRY -> failedMessageId = item.id
                                MessageMetadata.TapAction.OFFER_SCHEDULE_ACTIONS -> scheduledMessageId = item.id
                                MessageMetadata.TapAction.TOGGLE_DETAILS ->
                                    expandedId = MessageMetadata.onTap(expandedId, item.id, selectionActive = false)
                            }
                        },
                        onLongClick = { viewModel.enterSelection(item.id) },
                        selectionActive = selection.active,
                    )
                }
            }
        }
    }

    // Retry/Delete choice for a tapped failed bubble. Retry re-dispatches
    // the SAME row through the normal send path (the bubble flips back to
    // Sending in place, no duplicate); Delete stages an undoable delete like
    // every other delete in the app. Matches the screen's existing dialog
    // pattern (see the selection-delete confirmation below).
    failedMessageId?.let { messageId ->
        AlertDialog(
            onDismissRequest = { failedMessageId = null },
            title = { Text(stringResource(R.string.conversation_failed_dialog_title)) },
            text = { Text(stringResource(R.string.conversation_failed_dialog_message)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        failedMessageId = null
                        viewModel.retry(messageId)
                    },
                ) { Text(stringResource(R.string.action_retry)) }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        failedMessageId = null
                        viewModel.delete(messageId)
                    },
                ) { Text(stringResource(R.string.ui_action_delete)) }
            },
        )
    }

    // Send now / Edit time / Cancel for a tapped scheduled bubble - the
    // same dialog pattern as the failed-retry choice above.
    scheduledMessageId?.let { messageId ->
        AlertDialog(
            onDismissRequest = { scheduledMessageId = null },
            title = { Text(stringResource(R.string.conversation_scheduled_dialog_title)) },
            text = { Text(stringResource(R.string.conversation_scheduled_dialog_message)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        scheduledMessageId = null
                        viewModel.sendScheduledNow(messageId)
                    },
                ) { Text(stringResource(R.string.conversation_schedule_send_now)) }
            },
            dismissButton = {
                Row {
                    TextButton(
                        onClick = {
                            scheduledMessageId = null
                            scheduleEditTarget = messageId
                            showSchedulePicker = true
                        },
                    ) { Text(stringResource(R.string.conversation_schedule_edit_time)) }
                    TextButton(
                        onClick = {
                            scheduledMessageId = null
                            viewModel.cancelSchedule(messageId)
                        },
                    ) { Text(stringResource(R.string.conversation_schedule_cancel)) }
                }
            },
        )
    }

    if (showSchedulePicker) {
        ScheduleTimePicker(
            onDismiss = { showSchedulePicker = false },
            onConfirm = { atMs ->
                showSchedulePicker = false
                val target = scheduleEditTarget
                if (target == null) {
                    // scheduleSend consumes the draft like a send does.
                    viewModel.scheduleSend(draft, atMs)
                } else {
                    viewModel.editSchedule(target, atMs)
                }
            },
        )
    }

    if (confirmDelete) {
        val count = selection.count
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text(stringResource(R.string.selection_delete_messages_title)) },
            text = { Text(stringResource(R.string.selection_delete_messages_message, count)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmDelete = false
                        viewModel.deleteSelected()
                    },
                ) { Text(stringResource(R.string.ui_action_delete)) }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = false }) {
                    Text(stringResource(R.string.action_cancel))
                }
            },
        )
    }
}

/** Contextual top bar shown while message multi-select is active. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ConversationSelectionBar(
    selection: SelectionState<Long>,
    singleItem: ConversationItem?,
    onClose: () -> Unit,
    onDelete: () -> Unit,
    onCopy: () -> Unit,
    onSelectAll: () -> Unit,
    onCopyOtp: (String) -> Unit,
    onCreateRule: (body: String) -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }
    TopAppBar(
        title = {
            // titleMedium + single line: at the default titleLarge a
            // six-digit "999999 selected" wrapped out of view next to the
            // four action slots (measured on a 411dp-wide display).
            Text(
                text = stringResource(R.string.selection_count, selection.count),
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
            )
        },
        navigationIcon = {
            IconButton(onClick = onClose) {
                Icon(Icons.Outlined.Close, contentDescription = stringResource(R.string.action_exit_selection))
            }
        },
        actions = {
            IconButton(onClick = onDelete) {
                Icon(Icons.Outlined.Delete, contentDescription = stringResource(R.string.ui_action_delete))
            }
            IconButton(onClick = onCopy) {
                Icon(Icons.Outlined.ContentCopy, contentDescription = stringResource(R.string.action_copy_message))
            }
            IconButton(onClick = onSelectAll) {
                Icon(Icons.Outlined.SelectAll, contentDescription = stringResource(R.string.action_select_all))
            }
            if (singleItem != null) {
                IconButton(onClick = { menuOpen = true }) {
                    Icon(Icons.Outlined.MoreVert, contentDescription = stringResource(R.string.action_more_options))
                }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    singleItem.message?.extractedOtp?.let { otp ->
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.action_copy_otp)) },
                            leadingIcon = { Icon(Icons.Outlined.Password, contentDescription = null) },
                            onClick = {
                                menuOpen = false
                                onCopyOtp(otp)
                            },
                        )
                    }
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.action_add_rule)) },
                        leadingIcon = { Icon(Icons.Outlined.AddCircleOutline, contentDescription = null) },
                        onClick = {
                            menuOpen = false
                            onCreateRule(singleItem.body)
                        },
                    )
                }
            }
        },
    )
}

/** Replaces the composer for one-way senders (alphanumeric ids, short codes). */
@Composable
private fun NotRepliableRow() {
    Surface(color = MaterialTheme.colorScheme.surfaceVariant) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center,
        ) {
            Icon(
                Icons.Outlined.Info,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = stringResource(R.string.conversation_not_repliable),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun DateSeparator(timestamp: Long) {
    Box(Modifier.fillMaxWidth().padding(vertical = 8.dp), contentAlignment = Alignment.Center) {
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.surfaceVariant,
        ) {
            Text(
                text = RelativeTime.dateLabel(timestamp),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
            )
        }
    }
}

/**
 * One chat bubble. Alignment and colors come from the PERSISTED direction
 * ([ConversationItem.outgoing]): outgoing right / `primaryContainer`,
 * incoming left / `surfaceVariant` - stable across app restarts.
 *
 * Tapping (outside selection mode) toggles ONE expansion region below the
 * bubble holding the metadata line (exact timestamp; delivery status for
 * outgoing, category for incoming) and, when the message has parsed
 * extraction details, the detail card - a single expander, so metadata and
 * the transaction/OTP card never fight over the tap.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun MessageBubble(
    item: ConversationItem,
    highlighted: Boolean,
    selected: Boolean,
    showDetails: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    expanded: Boolean = false,
    selectionActive: Boolean = false,
) {
    val alignment = if (item.outgoing) Alignment.CenterEnd else Alignment.CenterStart
    val bubbleColor =
        when {
            selected -> MaterialTheme.colorScheme.secondaryContainer
            item.outgoing -> MaterialTheme.colorScheme.primaryContainer
            else -> MaterialTheme.colorScheme.surfaceVariant
        }
    val textColor =
        when {
            selected -> MaterialTheme.colorScheme.onSecondaryContainer
            item.outgoing -> MaterialTheme.colorScheme.onPrimaryContainer
            else -> MaterialTheme.colorScheme.onSurfaceVariant
        }

    // Background behind the opened message. The screen holds `highlighted`
    // true for HIGHLIGHT_HOLD_MS, then this fades to transparent. The tint is
    // a primary-color wash - the previous secondaryContainer was visually
    // indistinguishable from the message bubbles, so the "highlight" was
    // invisible in practice.
    val highlightColor by animateColorAsState(
        targetValue =
            if (highlighted) {
                MaterialTheme.colorScheme.primary.copy(alpha = 0.22f)
            } else {
                Color.Transparent
            },
        animationSpec = tween(durationMillis = HIGHLIGHT_FADE_MS),
        label = "message_highlight",
    )

    // The whole row also reflects selection so it is visible at a glance.
    val rowColor =
        if (selected) {
            MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.4f)
        } else {
            highlightColor
        }

    Box(
        modifier =
            Modifier
                .fillMaxWidth()
                .background(rowColor)
                .padding(horizontal = 16.dp, vertical = 3.dp),
        contentAlignment = alignment,
    ) {
        Column(
            modifier = Modifier.widthIn(max = 320.dp),
            horizontalAlignment = if (item.outgoing) Alignment.End else Alignment.Start,
        ) {
            Surface(
                shape =
                    RoundedCornerShape(
                        topStart = 20.dp,
                        topEnd = 20.dp,
                        bottomStart = if (item.outgoing) 20.dp else 6.dp,
                        bottomEnd = if (item.outgoing) 6.dp else 20.dp,
                    ),
                color = bubbleColor,
                modifier =
                    Modifier
                        // Comfortable touch target for the tap-to-reveal gesture.
                        .defaultMinSize(minHeight = 48.dp)
                        .combinedClickable(
                            onClick = onClick,
                            onClickLabel =
                                stringResource(
                                    if (expanded) {
                                        R.string.conversation_hide_message_details
                                    } else {
                                        R.string.conversation_show_message_details
                                    },
                                ),
                            onLongClick = onLongClick,
                            onLongClickLabel = stringResource(R.string.inbox_row_actions),
                        ),
            ) {
                Column(Modifier.padding(horizontal = 16.dp, vertical = 10.dp)) {
                    Text(text = item.body, style = MaterialTheme.typography.bodyLarge, color = textColor)
                    Text(
                        text = item.timeLabel,
                        style = MaterialTheme.typography.labelSmall,
                        color = textColor.copy(alpha = 0.7f),
                        modifier = Modifier.align(Alignment.End).padding(top = 2.dp),
                    )
                    // In-flight, failed and scheduled sends stay visible on
                    // the bubble itself; resolved statuses live in the
                    // metadata line.
                    when (item.deliveryStatus) {
                        DeliveryStatus.SCHEDULED -> {
                            val context = LocalContext.current
                            val is24Hour = remember { DateFormat.is24HourFormat(context) }
                            val at = item.message?.scheduledAt ?: item.timestamp
                            Text(
                                text =
                                    stringResource(
                                        R.string.conversation_scheduled_for,
                                        MessageMetadata.timestampLabel(at, is24Hour),
                                    ),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.tertiary,
                                modifier = Modifier.align(Alignment.End),
                            )
                        }
                        DeliveryStatus.SENDING, DeliveryStatus.FAILED ->
                            Text(
                                text =
                                    if (item.deliveryStatus == DeliveryStatus.FAILED) {
                                        stringResource(R.string.conversation_not_sent)
                                    } else {
                                        stringResource(R.string.conversation_sending)
                                    },
                                style = MaterialTheme.typography.labelSmall,
                                color =
                                    if (item.deliveryStatus == DeliveryStatus.FAILED) {
                                        MaterialTheme.colorScheme.error
                                    } else {
                                        textColor.copy(alpha = 0.7f)
                                    },
                                modifier = Modifier.align(Alignment.End),
                            )
                        else -> Unit
                    }
                }
            }
            AnimatedVisibility(visible = expanded) {
                Column(horizontalAlignment = if (item.outgoing) Alignment.End else Alignment.Start) {
                    MessageMetadataLine(item)
                    if (DetailCardVisibility.shouldShow(item.details, showDetails)) {
                        ParsedDetailCard(details = item.details)
                    }
                }
            }
        }
    }
}

/**
 * The unobtrusive metadata line revealed under a tapped bubble: exact
 * timestamp (honouring the device's 12/24-hour setting), plus the persisted
 * delivery status for outgoing messages or the category for incoming ones.
 */
@Composable
private fun MessageMetadataLine(item: ConversationItem) {
    val context = LocalContext.current
    val is24Hour = remember { DateFormat.is24HourFormat(context) }
    val timestamp = remember(item.id) { MessageMetadata.timestampLabel(item.timestamp, is24Hour) }
    val detail =
        if (item.outgoing) {
            stringResource(deliveryStatusLabelRes(item.deliveryStatus))
        } else {
            item.message?.let { message ->
                message.subCategory?.let { categoryLabel(it.name) }
                    ?: categoryLabel(message.category.name)
            }
        }
    Text(
        text = listOfNotNull(timestamp, detail, item.simLabel).joinToString(" · "),
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 4.dp, start = 4.dp, end = 4.dp),
    )
}

/**
 * The bubble status line for an outgoing message, from its PERSISTED status -
 * never derived from the tap or the passage of time. No failure recorded and
 * no delivery report (reports off, or the carrier sent none) reads honestly
 * as "Sent", never "Delivered".
 */
internal fun deliveryStatusLabelRes(status: DeliveryStatus?): Int =
    when (status) {
        DeliveryStatus.SCHEDULED -> R.string.conversation_scheduled
        DeliveryStatus.SENDING -> R.string.conversation_sending
        DeliveryStatus.DELIVERED -> R.string.conversation_delivered
        DeliveryStatus.FAILED -> R.string.conversation_not_sent
        DeliveryStatus.SENT, null -> R.string.conversation_sent
    }

/** "BANK_ALERT" → "Bank alert" (enum names are already user-meaningful). */
private fun categoryLabel(name: String): String = name.lowercase().replace('_', ' ').replaceFirstChar { it.uppercaseChar() }

/** Expandable card showing parsed transaction / OTP fields under a bubble. */
@Composable
private fun ParsedDetailCard(details: Map<String, String>) {
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.secondaryContainer,
        modifier = Modifier.padding(top = 4.dp),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                text = stringResource(R.string.conversation_details_title),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
            )
            details.forEach { (key, value) ->
                Row {
                    Text(
                        text = key.replace('_', ' ').replaceFirstChar { it.uppercaseChar() } + ": ",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                    )
                    val kind =
                        when (key) {
                            "amount" -> amountKindOf(details)
                            "balance" -> AmountKind.BALANCE
                            else -> null
                        }
                    val amount = value.toDoubleOrNull()
                    if (kind != null && amount != null) {
                        AmountText(
                            amount = amount,
                            kind = kind,
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    } else {
                        Text(
                            text = value,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSecondaryContainer,
                        )
                    }
                }
            }
        }
    }
}
