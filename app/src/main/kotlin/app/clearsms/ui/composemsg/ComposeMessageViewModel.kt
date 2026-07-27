package app.clearsms.ui.composemsg

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.clearsms.data.prefs.SettingsRepository
import app.clearsms.di.IoDispatcher
import app.clearsms.sms.SmsSender
import app.clearsms.ui.conversation.SendStatus
import app.clearsms.ui.conversation.SentMessageWatcher
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

data class ComposeUiState(
    val recipient: String = "",
    /** The chosen contact when the recipient came from a suggestion (name shown, number sent). */
    val picked: ContactSuggestion? = null,
    val body: String = "",
    val suggestions: List<ContactSuggestion> = emptyList(),
    /** Lifecycle of the current send; null before the first attempt. */
    val sendStatus: SendStatus? = null,
)

@OptIn(FlowPreview::class)
@HiltViewModel
class ComposeMessageViewModel
    @Inject
    constructor(
        savedStateHandle: SavedStateHandle,
        private val smsSender: SmsSender,
        private val sentMessageWatcher: SentMessageWatcher,
        private val settings: SettingsRepository,
        private val contactSuggestions: ContactSuggestions,
        @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
    ) : ViewModel() {
        private val state =
            MutableStateFlow(
                ComposeUiState(
                    recipient = savedStateHandle.get<String>("recipient").orEmpty(),
                    body = savedStateHandle.get<String>("body").orEmpty(),
                ),
            )
        val uiState: StateFlow<ComposeUiState> = state.asStateFlow()

        private val recipientQuery = MutableStateFlow("")

        val suggestions: StateFlow<List<ContactSuggestion>> =
            recipientQuery
                .debounce(200)
                .map { query -> contactSuggestions.search(query) }
                .flowOn(ioDispatcher)
                .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

        fun onRecipientChange(value: String) {
            applySelection(currentSelection.edit(value))
            recipientQuery.value = value
        }

        fun pickSuggestion(suggestion: ContactSuggestion) {
            applySelection(currentSelection.pick(suggestion))
            recipientQuery.value = ""
        }

        /** Removes the picked contact and returns to manual entry. */
        fun clearPicked() {
            applySelection(currentSelection.clear())
            recipientQuery.value = ""
        }

        private val currentSelection: RecipientSelection
            get() = RecipientSelection(state.value.recipient, state.value.picked)

        private fun applySelection(selection: RecipientSelection) {
            state.value = state.value.copy(recipient = selection.destination, picked = selection.picked)
        }

        fun onBodyChange(value: String) {
            state.value = state.value.copy(body = value)
        }

        /**
         * Dispatches the message and resolves [ComposeUiState.sendStatus]
         * from the persisted message status: [SmsSender] writes the outgoing
         * row at Sending, and [SentMessageWatcher] resolves it to Sent or
         * Failed from the recorded radio reports. Retrying is calling [send]
         * again.
         */
        fun send() {
            val current = state.value
            if (current.recipient.isBlank() || current.body.isBlank()) return
            if (current.sendStatus == SendStatus.SENDING) return
            state.value = current.copy(sendStatus = SendStatus.SENDING)
            viewModelScope.launch(ioDispatcher) {
                val status =
                    try {
                        val signature = settings.signature.first()
                        val fullBody =
                            if (signature.isNotBlank()) "${current.body}\n$signature" else current.body
                        val messageId = smsSender.send(current.recipient.trim(), fullBody)
                        sentMessageWatcher.await(messageId)
                    } catch (_: Exception) {
                        SendStatus.FAILED
                    }
                state.value = state.value.copy(sendStatus = status)
            }
        }
    }
