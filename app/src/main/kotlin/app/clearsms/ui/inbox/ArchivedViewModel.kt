package app.clearsms.ui.inbox

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.clearsms.data.db.MessageEntity
import app.clearsms.data.prefs.SettingsRepository
import app.clearsms.data.repository.MessageRepository
import app.clearsms.data.repository.UndoManager
import app.clearsms.data.senderid.SenderIdStore
import app.clearsms.di.IoDispatcher
import app.clearsms.sms.ContactsSource
import app.clearsms.ui.common.RelativeTime
import app.clearsms.ui.common.UndoUiEvent
import app.clearsms.ui.components.SelectionState
import app.clearsms.ui.components.SenderDisplay
import app.clearsms.ui.components.brandGlyphFor
import app.clearsms.ui.components.resolveSenderDisplay
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject

data class ArchivedUiState(
    val items: List<InboxItem> = emptyList(),
    val richAvatars: Boolean = true,
    val loaded: Boolean = false,
)

/** Archived conversations: view, unarchive and delete (per row or selected). */
@HiltViewModel
class ArchivedViewModel
    @Inject
    constructor(
        private val messageRepository: MessageRepository,
        private val undoManager: UndoManager,
        private val senderIdStore: SenderIdStore,
        private val contactsSource: ContactsSource,
        settings: SettingsRepository,
        @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
    ) : ViewModel() {
        private val displayCache = ConcurrentHashMap<String, SenderDisplay>()

        /** One-shot undo snackbar requests (a delete was just staged). */
        private val undoEvents = Channel<UndoUiEvent>(Channel.BUFFERED)
        val undoEventFlow: Flow<UndoUiEvent> = undoEvents.receiveAsFlow()

        /** Multi-select over thread ids (rows are threads). */
        private val selectionState = MutableStateFlow(SelectionState<Long>())
        val selection: StateFlow<SelectionState<Long>> = selectionState.asStateFlow()

        val uiState: StateFlow<ArchivedUiState> =
            combine(
                messageRepository.observeArchived(),
                settings.showRichAvatars,
            ) { messages, richAvatars ->
                ArchivedUiState(
                    items = messages.map { it.toInboxItem() },
                    richAvatars = richAvatars,
                    loaded = true,
                )
            }.flowOn(ioDispatcher)
                .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ArchivedUiState())

        fun unarchive(threadId: Long) {
            viewModelScope.launch(ioDispatcher) {
                messageRepository.archiveThreads(listOf(threadId), archived = false)
            }
        }

        fun delete(threadId: Long) {
            viewModelScope.launch(ioDispatcher) {
                val count = undoManager.stageDeleteThreads(listOf(threadId))
                if (count > 0) undoEvents.send(UndoUiEvent.Deleted(count))
            }
        }

        /** Reverts the last staged delete while its snackbar is showing. */
        fun undo() {
            viewModelScope.launch(ioDispatcher) { undoManager.undo() }
        }

        // region selection

        fun enterSelection(threadId: Long) {
            selectionState.update { if (it.active) it.toggle(threadId) else it.enter(threadId) }
        }

        fun toggleSelection(threadId: Long) {
            selectionState.update { it.toggle(threadId) }
        }

        fun exitSelection() {
            selectionState.value = SelectionState()
        }

        fun selectAll() {
            viewModelScope.launch(ioDispatcher) {
                val ids = messageRepository.archivedThreadIds()
                selectionState.update { it.withAll(ids) }
            }
        }

        fun unarchiveSelected() {
            val ids = selectionState.value.selected.toList()
            exitSelection()
            viewModelScope.launch(ioDispatcher) {
                messageRepository.archiveThreads(ids, archived = false)
            }
        }

        fun deleteSelected() {
            val ids = selectionState.value.selected.toList()
            exitSelection()
            viewModelScope.launch(ioDispatcher) {
                val count = undoManager.stageDeleteThreads(ids)
                if (count > 0) undoEvents.send(UndoUiEvent.Deleted(count))
            }
        }

        // endregion

        private fun MessageEntity.toInboxItem(): InboxItem {
            val display =
                displayCache.getOrPut(sender) {
                    resolveSenderDisplay(
                        sender = sender,
                        contactLookup = contactsSource::lookup,
                        directoryLookup = { senderIdStore.lookup(it)?.name },
                    )
                }
            return InboxItem(
                message = this,
                display = display,
                glyph = brandGlyphFor(subCategory, display.name),
                timeLabel = RelativeTime.format(timestamp),
            )
        }
    }
