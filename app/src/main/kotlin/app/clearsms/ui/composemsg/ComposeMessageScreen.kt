package app.clearsms.ui.composemsg

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.clearsms.R
import app.clearsms.ui.components.AttachmentPickerSheet
import app.clearsms.ui.components.MessageComposerBar
import app.clearsms.ui.components.ScheduleTimePicker
import app.clearsms.ui.components.SenderAvatar
import app.clearsms.ui.components.TooltipIconButton
import app.clearsms.ui.components.rememberAttachmentLaunchers
import app.clearsms.ui.conversation.SendStatus

/** New message: recipient with contact suggestions, body, signature-aware send. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ComposeMessageScreen(
    onBack: () -> Unit,
    onOpenConversation: (Long) -> Unit,
    viewModel: ComposeMessageViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val suggestions by viewModel.suggestions.collectAsStateWithLifecycle()
    val simState by viewModel.simState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val notSentMessage = stringResource(R.string.message_not_sent)
    val retryLabel = stringResource(R.string.action_retry)
    var showSchedulePicker by rememberSaveable { mutableStateOf(false) }

    // Compose-bar attachments being staged for an MMS send, and the
    // attach sheet's visibility. Attachment state deliberately does not
    // persist in drafts this wave (text does): no rememberSaveable.
    val attachments by viewModel.attachments.collectAsStateWithLifecycle()
    val attachmentError by viewModel.attachmentError.collectAsStateWithLifecycle()
    var showAttachmentSheet by remember { mutableStateOf(false) }
    val attachmentLaunchers =
        rememberAttachmentLaunchers(
            onPicked = viewModel::addAttachments,
            cameraUriProvider = viewModel::cameraUri,
            onCameraDone = viewModel::onCameraResult,
        )

    // Send feedback is IMMEDIATE and lives in the thread: the box clears on
    // tap (or on a confirmed schedule), and a successful dispatch navigates
    // into the new conversation, where the Sending->Sent bubble - or the
    // "Scheduled for <time>" bubble - reports the outcome. Only a dispatch
    // failure keeps the user here, with the body restored and a Retry
    // affordance.
    LaunchedEffect(Unit) {
        viewModel.openThreadFlow.collect { threadId -> onOpenConversation(threadId) }
    }
    LaunchedEffect(state.sendStatus) {
        if (state.sendStatus == SendStatus.FAILED) {
            val result =
                snackbarHostState.showSnackbar(
                    message = notSentMessage,
                    actionLabel = retryLabel,
                    duration = SnackbarDuration.Long,
                )
            if (result == SnackbarResult.ActionPerformed) viewModel.send()
        }
    }

    // One-time discoverability nudge: the first send from any compose bar
    // (per install) points at the invisible long-press-to-schedule.
    val scheduleTip = stringResource(R.string.conversation_schedule_tip)
    LaunchedEffect(Unit) {
        viewModel.scheduleTipFlow.collect { snackbarHostState.showSnackbar(scheduleTip) }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.compose_title)) },
                navigationIcon = {
                    TooltipIconButton(
                        label = stringResource(R.string.action_back),
                        onClick = onBack,
                        icon = Icons.AutoMirrored.Outlined.ArrowBack,
                    )
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
        bottomBar = {
            // The SAME compose bar as the conversation screen: SIM
            // indicator, send, long-press to schedule.
            MessageComposerBar(
                draft = state.body,
                onDraftChange = viewModel::onBodyChange,
                onSend = viewModel::send,
                sim = simState,
                onCycleSim = viewModel::cycleSim,
                onScheduleSend = { showSchedulePicker = true },
                attachments = attachments,
                onAttachClick = { showAttachmentSheet = true },
                onRemoveAttachment = viewModel::removeAttachment,
                attachmentError = attachmentError,
            )
        },
    ) { padding ->
        Column(
            modifier =
                Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(horizontal = 16.dp),
        ) {
            if (state.picked != null) {
                val picked = state.picked!!
                // The picked contact renders as NAME + chosen number; the value
                // sent stays the raw number held in state.recipient.
                ListItem(
                    modifier = Modifier.fillMaxWidth(),
                    leadingContent = {
                        SenderAvatar(
                            name = picked.name,
                            richAvatars = true,
                            photoUri = picked.photoUri,
                        )
                    },
                    headlineContent = { Text(picked.name) },
                    supportingContent = {
                        Text(
                            picked.number,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    },
                    trailingContent = {
                        IconButton(onClick = viewModel::clearPicked) {
                            Icon(
                                Icons.Outlined.Close,
                                contentDescription = stringResource(R.string.compose_change_recipient),
                            )
                        }
                    },
                )
            } else {
                // Forward / inbound share arrive with a prefilled BODY and an
                // empty recipient: focus goes straight to the recipient field
                // so the next keystroke picks who it goes to.
                val recipientFocus = remember { FocusRequester() }
                OutlinedTextField(
                    value = state.recipient,
                    onValueChange = viewModel::onRecipientChange,
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .padding(top = 16.dp)
                            .focusRequester(recipientFocus),
                    label = { Text(stringResource(R.string.compose_recipient)) },
                    singleLine = true,
                )
                LaunchedEffect(Unit) { recipientFocus.requestFocus() }
            }
            if (state.picked == null && suggestions.isNotEmpty()) {
                LazyColumn(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                    items(suggestions, key = { it.name + it.number }) { suggestion ->
                        ListItem(
                            modifier = Modifier.clickable { viewModel.pickSuggestion(suggestion) },
                            leadingContent = {
                                SenderAvatar(
                                    name = suggestion.name,
                                    richAvatars = true,
                                    photoUri = suggestion.photoUri,
                                )
                            },
                            headlineContent = { Text(suggestion.name) },
                            supportingContent = {
                                Text(
                                    suggestion.number,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            },
                        )
                    }
                }
            }
        }
    }

    // The compose-bar "+": Photo (photo picker) / Camera / Any file.
    if (showAttachmentSheet) {
        AttachmentPickerSheet(
            onDismiss = { showAttachmentSheet = false },
            launchers = attachmentLaunchers,
        )
    }

    if (showSchedulePicker) {
        ScheduleTimePicker(
            onDismiss = { showSchedulePicker = false },
            onConfirm = { atMs ->
                showSchedulePicker = false
                viewModel.schedule(atMs)
            },
        )
    }
}
