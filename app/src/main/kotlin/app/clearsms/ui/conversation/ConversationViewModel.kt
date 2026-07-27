package app.clearsms.ui.conversation

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingData
import androidx.paging.cachedIn
import androidx.paging.map
import app.clearsms.data.db.MessageEntity
import app.clearsms.data.prefs.SettingsRepository
import app.clearsms.data.repository.MessageRepository
import app.clearsms.data.senderid.SenderIdStore
import app.clearsms.di.IoDispatcher
import app.clearsms.sms.ContactsSource
import app.clearsms.sms.SenderRepliability
import app.clearsms.sms.SmsSender
import app.clearsms.ui.common.RelativeTime
import app.clearsms.ui.components.BrandGlyph
import app.clearsms.ui.components.SelectionState
import app.clearsms.ui.components.SenderDisplay
import app.clearsms.ui.components.brandGlyphFor
import app.clearsms.ui.components.resolveSenderDisplay
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
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
    /** Precomputed in the mapper so composition never formats dates. */
    val timeLabel: String = "",
)

data class ConversationUiState(
    val title: String = "",
    val address: String = "",
    val photoUri: String? = null,
    val isKnownSender: Boolean = false,
    val glyph: BrandGlyph = BrandGlyph.NONE,
    val richAvatars: Boolean = true,
    /** Replies typed on this screen, newest last (paged items carry the rest). */
    val localItems: List<ConversationItem> = emptyList(),
    /** Message to scroll to and briefly highlight, from search / notification. */
    val highlightMessageId: Long? = null,
    /** False for one-way senders (alphanumeric ids, short codes): composer is hidden. */
    val repliable: Boolean = false,
    val sending: Boolean = false,
    val loaded: Boolean = false,
)

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class ConversationViewModel
    @Inject
    constructor(
        savedStateHandle: SavedStateHandle,
        private val messageRepository: MessageRepository,
        private val senderIdStore: SenderIdStore,
        private val contactsSource: ContactsSource,
        private val smsSender: SmsSender,
        settings: SettingsRepository,
        private val json: Json,
        @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
    ) : ViewModel() {
        private val threadId: Long = checkNotNull(savedStateHandle["threadId"])

        /** Optional target from the nav argument; -1 (the default) means none. */
        private val highlightMessageId: Long? =
            savedStateHandle.get<Long>("messageId")?.takeIf { it > 0 }

        /** Replies sent from this screen; the platform layer owns system persistence. */
        private val sentLocally = MutableStateFlow<List<ConversationItem>>(emptyList())
        private val sending = MutableStateFlow(false)
        private var nextLocalId = -1L

        /** Multi-select over message ids within the thread. */
        private val selectionState = MutableStateFlow(SelectionState<Long>())
        val selection: StateFlow<SelectionState<Long>> = selectionState.asStateFlow()

        /**
         * Paged thread messages, newest first (the screen renders them with
         * `reverseLayout`), so a 14k-message thread only ever materializes the
         * visible window. When navigation carries a highlight target, paging
         * starts at its position so the message is in the first load.
         */
        val pagedItems: Flow<PagingData<ConversationItem>> =
            flow { emit(initialPosition()) }
                .flatMapLatest { position ->
                    Pager(
                        config =
                            PagingConfig(
                                pageSize = PAGE_SIZE,
                                initialLoadSize = PAGE_SIZE * 2,
                                enablePlaceholders = false,
                            ),
                        initialKey = position,
                        pagingSourceFactory = { messageRepository.pagedThread(threadId) },
                    ).flow
                }.map { data -> data.map { it.toItem() } }
                .flowOn(ioDispatcher)
                .cachedIn(viewModelScope)

        val uiState: StateFlow<ConversationUiState> =
            combine(
                flow { emit(messageRepository.firstInThread(threadId)) },
                sentLocally,
                sending,
                settings.showRichAvatars,
            ) { first, local, isSending, richAvatars ->
                val display = first?.sender?.let { resolveDisplay(it) }
                ConversationUiState(
                    title = display?.name.orEmpty(),
                    address = first?.sender.orEmpty(),
                    photoUri = display?.photoUri,
                    isKnownSender = display?.isKnownSender ?: false,
                    glyph = brandGlyphFor(first?.subCategory, display?.name.orEmpty()),
                    richAvatars = richAvatars,
                    localItems = local,
                    highlightMessageId = highlightMessageId,
                    repliable = first?.sender?.let { SenderRepliability.isRepliable(it) } ?: false,
                    sending = isSending,
                    loaded = first != null,
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
                            timeLabel = RelativeTime.format(System.currentTimeMillis()),
                        )
                } finally {
                    sending.value = false
                }
            }
        }

        fun delete(messageId: Long) {
            viewModelScope.launch(ioDispatcher) { messageRepository.deleteMessages(listOf(messageId)) }
        }

        // region selection

        fun enterSelection(messageId: Long) {
            selectionState.update { if (it.active) it.toggle(messageId) else it.enter(messageId) }
        }

        fun toggleSelection(messageId: Long) {
            selectionState.update { it.toggle(messageId) }
        }

        fun exitSelection() {
            selectionState.value = SelectionState()
        }

        /** Selects every stored message of the thread (queried, not just loaded pages). */
        fun selectAll() {
            viewModelScope.launch(ioDispatcher) {
                val ids = messageRepository.messageIdsInThread(threadId)
                selectionState.update { it.withAll(ids) }
            }
        }

        /** Deletes the selected messages (batched, synced to the system provider). */
        fun deleteSelected() {
            val ids = selectionState.value.selected.toList()
            exitSelection()
            viewModelScope.launch(ioDispatcher) { messageRepository.deleteMessages(ids) }
        }

        /**
         * Concatenates the selected message bodies in chronological order and
         * hands the text to [onReady] on the main thread (for the clipboard).
         */
        fun copySelected(onReady: (String) -> Unit) {
            val ids = selectionState.value.selected.toList()
            exitSelection()
            viewModelScope.launch {
                val text =
                    withContext(ioDispatcher) {
                        messageRepository.bodiesInOrder(ids).joinToString(separator = "\n\n")
                    }
                onReady(text)
            }
        }

        // endregion

        private fun resolveDisplay(sender: String): SenderDisplay =
            resolveSenderDisplay(
                sender = sender,
                contactLookup = contactsSource::lookup,
                directoryLookup = { senderIdStore.lookup(it)?.name },
            )

        private suspend fun initialPosition(): Int? =
            highlightMessageId
                ?.let { messageRepository.positionInThread(threadId, it) }
                ?.takeIf { it > 0 }

        private fun MessageEntity.toItem(): ConversationItem =
            ConversationItem(
                id = id,
                body = body,
                timestamp = timestamp,
                outgoing = false,
                message = this,
                details = parseDetails(extractedDataJson),
                timeLabel = RelativeTime.format(timestamp),
            )

        private fun parseDetails(raw: String?): Map<String, String> =
            raw?.let {
                try {
                    json.decodeFromString(MapSerializer(String.serializer(), String.serializer()), it)
                } catch (_: Exception) {
                    emptyMap()
                }
            } ?: emptyMap()

        private companion object {
            const val PAGE_SIZE = 60
        }
    }
