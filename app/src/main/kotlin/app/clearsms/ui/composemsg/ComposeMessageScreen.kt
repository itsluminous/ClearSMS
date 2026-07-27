package app.clearsms.ui.composemsg

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.Send
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
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
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.clearsms.R
import app.clearsms.ui.components.SenderAvatar
import app.clearsms.ui.conversation.SendStatus

/** New message: recipient with contact suggestions, body, signature-aware send. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ComposeMessageScreen(
    onBack: () -> Unit,
    viewModel: ComposeMessageViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val suggestions by viewModel.suggestions.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val sentMessage = stringResource(R.string.message_sent)
    val notSentMessage = stringResource(R.string.message_not_sent)
    val retryLabel = stringResource(R.string.action_retry)

    // The send resolves asynchronously from the persisted message status:
    // confirm honestly ("Message sent" only without a recorded failure),
    // offer Retry on failure, and only leave the screen after a success.
    LaunchedEffect(state.sendStatus) {
        when (state.sendStatus) {
            SendStatus.SENT -> {
                snackbarHostState.showSnackbar(sentMessage, duration = SnackbarDuration.Short)
                onBack()
            }
            SendStatus.FAILED -> {
                val result =
                    snackbarHostState.showSnackbar(
                        message = notSentMessage,
                        actionLabel = retryLabel,
                        duration = SnackbarDuration.Long,
                    )
                if (result == SnackbarResult.ActionPerformed) viewModel.send()
            }
            else -> Unit
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.compose_title)) },
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
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = viewModel::send,
                icon = {
                    Icon(
                        Icons.AutoMirrored.Outlined.Send,
                        contentDescription = null,
                    )
                },
                text = { Text(stringResource(R.string.action_send)) },
            )
        },
    ) { padding ->
        Column(
            modifier =
                Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(16.dp)
                    .imePadding(),
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
                OutlinedTextField(
                    value = state.recipient,
                    onValueChange = viewModel::onRecipientChange,
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text(stringResource(R.string.compose_recipient)) },
                    singleLine = true,
                )
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
            OutlinedTextField(
                value = state.body,
                onValueChange = viewModel::onBodyChange,
                modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                label = { Text(stringResource(R.string.compose_message)) },
                minLines = 4,
            )
        }
    }
}
