package app.clearsms.ui.conversation

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.clearsms.data.db.MessageEntity
import app.clearsms.data.repository.MessageRepository
import app.clearsms.data.senderid.SenderIdStore
import app.clearsms.di.IoDispatcher
import app.clearsms.sms.SmsSender
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import javax.inject.Inject

/** One bubble in the conversation: a stored incoming message or a locally sent reply. */
data class ConversationItem(
    val id: Long,
    val body: String,
    val timestamp: Long,
    val outgoing: Boolean,
    /** Backing entity for incoming messages (null for locally sent replies). */
    val message: MessageEntity? = null,
    /** Parsed extraction details (amount, bank, otp_code…) for the detail card. */
    val details: Map<String, String> = emptyMap(),
)

data class ConversationUiState(
    val title: String = "",
    val address: String = "",
    val items: List<ConversationItem> = emptyList(),
    val sending: Boolean = false,
    val loaded: Boolean = false,
)

@HiltViewModel
class ConversationViewModel
    @Inject
    constructor(
        savedStateHandle: SavedStateHandle,
        private val messageRepository: MessageRepository,
        private val senderIdStore: SenderIdStore,
        private val smsSender: SmsSender,
        private val json: Json,
        @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
    ) : ViewModel() {
        private val threadId: Long = checkNotNull(savedStateHandle["threadId"])

        /** Replies sent from this screen; the platform layer owns system persistence. */
        private val sentLocally = MutableStateFlow<List<ConversationItem>>(emptyList())
        private val sending = MutableStateFlow(false)
        private var nextLocalId = -1L

        val uiState: StateFlow<ConversationUiState> =
            combine(
                messageRepository.observeThread(threadId),
                sentLocally,
                sending,
            ) { stored, local, isSending ->
                val first = stored.firstOrNull()
                val items =
                    (stored.map { it.toItem() } + local).sortedBy { it.timestamp }
                ConversationUiState(
                    title = first?.sender?.let { senderIdStore.lookup(it)?.name ?: it }.orEmpty(),
                    address = first?.sender.orEmpty(),
                    items = items,
                    sending = isSending,
                    loaded = true,
                )
            }.flowOn(ioDispatcher)
                .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ConversationUiState())

        fun send(body: String) {
            val destination = uiState.value.address
            if (destination.isBlank() || body.isBlank()) return
            viewModelScope.launch(ioDispatcher) {
                sending.value = true
                try {
                    smsSender.send(destination, body)
                    sentLocally.value = sentLocally.value +
                        ConversationItem(
                            id = nextLocalId--,
                            body = body,
                            timestamp = System.currentTimeMillis(),
                            outgoing = true,
                        )
                } finally {
                    sending.value = false
                }
            }
        }

        fun delete(messageId: Long) {
            viewModelScope.launch(ioDispatcher) { messageRepository.delete(messageId) }
        }

        private fun MessageEntity.toItem(): ConversationItem =
            ConversationItem(
                id = id,
                body = body,
                timestamp = timestamp,
                outgoing = false,
                message = this,
                details = parseDetails(extractedDataJson),
            )

        private fun parseDetails(raw: String?): Map<String, String> =
            raw?.let {
                try {
                    json.decodeFromString(MapSerializer(String.serializer(), String.serializer()), it)
                } catch (_: Exception) {
                    emptyMap()
                }
            } ?: emptyMap()
    }
