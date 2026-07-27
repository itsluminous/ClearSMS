package app.clearsms.ui.conversation

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.Send
import androidx.compose.material.icons.outlined.AddCircleOutline
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Password
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.clearsms.R
import app.clearsms.ui.common.RelativeTime
import app.clearsms.ui.components.AmountKind
import app.clearsms.ui.components.AmountText
import app.clearsms.ui.components.SenderAvatar
import app.clearsms.ui.components.amountKindOf
import kotlinx.coroutines.delay

/** How long the opened-message highlight takes to fade out. */
private const val HIGHLIGHT_FADE_MS = 1_500

/** Conversation thread: chat bubbles, date separators, parsed-detail cards and reply. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConversationScreen(
    onBack: () -> Unit,
    onCreateRule: (sender: String, body: String) -> Unit,
    viewModel: ConversationViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    var draft by rememberSaveable { mutableStateOf("") }
    var sheetItem by remember { mutableStateOf<ConversationItem?>(null) }
    val listState = rememberLazyListState()

    // A message id arriving via navigation (search result / notification) is
    // scrolled to and briefly highlighted exactly once per screen instance.
    var highlightConsumed by rememberSaveable { mutableStateOf(false) }
    var highlightedItemId by remember { mutableStateOf<Long?>(null) }

    LaunchedEffect(state.items.size) {
        if (state.items.isEmpty()) return@LaunchedEffect
        val targetIndex =
            if (highlightConsumed) {
                null
            } else {
                highlightIndexFor(state.items.map { it.id }, state.highlightMessageId)
            }
        if (targetIndex != null) {
            highlightConsumed = true
            highlightedItemId = state.highlightMessageId
            listState.scrollToItem(targetIndex)
        } else {
            listState.animateScrollToItem(state.items.size - 1)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        SenderAvatar(
                            name = state.title,
                            size = 36.dp,
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
        },
        bottomBar = {
            Row(
                modifier = Modifier.fillMaxWidth().imePadding().padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedTextField(
                    value = draft,
                    onValueChange = { draft = it },
                    modifier = Modifier.weight(1f),
                    placeholder = { Text(stringResource(R.string.conversation_reply_hint)) },
                    shape = RoundedCornerShape(28.dp),
                    maxLines = 4,
                )
                FilledIconButton(
                    onClick = {
                        viewModel.send(draft)
                        draft = ""
                    },
                    enabled = draft.isNotBlank() && !state.sending,
                ) {
                    Icon(
                        Icons.AutoMirrored.Outlined.Send,
                        contentDescription = stringResource(R.string.action_send),
                    )
                }
            }
        },
    ) { padding ->
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding =
                androidx.compose.foundation.layout
                    .PaddingValues(vertical = 8.dp),
        ) {
            itemsIndexed(state.items, key = { _, item -> item.id }) { index, item ->
                val previous = state.items.getOrNull(index - 1)
                if (previous == null || !RelativeTime.sameDay(previous.timestamp, item.timestamp)) {
                    DateSeparator(item.timestamp)
                }
                MessageBubble(
                    item = item,
                    highlighted = item.id == highlightedItemId,
                    onLongClick = { sheetItem = item },
                )
            }
        }
    }

    sheetItem?.let { item ->
        BubbleActionsSheet(
            item = item,
            onDismiss = { sheetItem = null },
            onDelete = {
                viewModel.delete(item.id)
                sheetItem = null
            },
            onCreateRule = {
                sheetItem = null
                onCreateRule(state.address, item.body)
            },
        )
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

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun MessageBubble(
    item: ConversationItem,
    highlighted: Boolean,
    onLongClick: () -> Unit,
) {
    var expanded by rememberSaveable(item.id) { mutableStateOf(false) }
    val alignment = if (item.outgoing) Alignment.CenterEnd else Alignment.CenterStart
    val bubbleColor =
        if (item.outgoing) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant
    val textColor =
        if (item.outgoing) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant

    // Temporary background behind the opened message: starts filled and fades
    // to transparent over ~1.5s once the row has been composed.
    var highlightVisible by remember(highlighted) { mutableStateOf(highlighted) }
    val highlightColor by animateColorAsState(
        targetValue =
            if (highlightVisible) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent,
        animationSpec = tween(durationMillis = HIGHLIGHT_FADE_MS),
        label = "message_highlight",
    )
    LaunchedEffect(highlighted) {
        if (highlighted) {
            delay(150)
            highlightVisible = false
        }
    }

    Box(
        modifier =
            Modifier
                .fillMaxWidth()
                .background(highlightColor)
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
                    Modifier.combinedClickable(
                        onClick = { if (item.details.isNotEmpty()) expanded = !expanded },
                        onLongClick = onLongClick,
                        onLongClickLabel = stringResource(R.string.inbox_row_actions),
                    ),
            ) {
                Column(Modifier.padding(horizontal = 16.dp, vertical = 10.dp)) {
                    Text(text = item.body, style = MaterialTheme.typography.bodyLarge, color = textColor)
                    Text(
                        text = RelativeTime.format(item.timestamp),
                        style = MaterialTheme.typography.labelSmall,
                        color = textColor.copy(alpha = 0.7f),
                        modifier = Modifier.align(Alignment.End).padding(top = 2.dp),
                    )
                }
            }
            AnimatedVisibility(visible = expanded && item.details.isNotEmpty()) {
                ParsedDetailCard(details = item.details)
            }
        }
    }
}

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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun BubbleActionsSheet(
    item: ConversationItem,
    onDismiss: () -> Unit,
    onDelete: () -> Unit,
    onCreateRule: () -> Unit,
) {
    val clipboard = LocalClipboardManager.current
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.padding(bottom = 24.dp)) {
            SheetAction(Icons.Outlined.ContentCopy, stringResource(R.string.action_copy_message)) {
                clipboard.setText(AnnotatedString(item.body))
                onDismiss()
            }
            item.message?.extractedOtp?.let { otp ->
                SheetAction(Icons.Outlined.Password, stringResource(R.string.action_copy_otp)) {
                    clipboard.setText(AnnotatedString(otp))
                    onDismiss()
                }
            }
            SheetAction(Icons.Outlined.Delete, stringResource(R.string.ui_action_delete), onDelete)
            SheetAction(Icons.Outlined.AddCircleOutline, stringResource(R.string.action_add_rule), onCreateRule)
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
