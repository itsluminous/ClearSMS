package app.clearsms.ui.inbox

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.clearsms.data.db.MessageEntity
import app.clearsms.data.prefs.SettingsRepository
import app.clearsms.data.repository.MessageRepository
import app.clearsms.data.senderid.SenderIdStore
import app.clearsms.di.IoDispatcher
import app.clearsms.sms.ContactsSource
import app.clearsms.ui.common.RelativeTime
import app.clearsms.ui.components.SenderDisplay
import app.clearsms.ui.components.brandGlyphFor
import app.clearsms.ui.components.resolveSenderDisplay
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject

data class BinUiState(
    val items: List<InboxItem> = emptyList(),
    val richAvatars: Boolean = true,
    val loaded: Boolean = false,
)

/** One-shot outcomes surfaced as snackbars on the bin screen. */
sealed interface BinEvent {
    /** Restore succeeded end to end (app DB and system provider). */
    data object Restored : BinEvent

    /**
     * Restore succeeded in-app but the system-provider re-insert failed or
     * was skipped (not the default SMS app) - said so via snackbar.
     */
    data object RestoredAppOnly : BinEvent
}

/**
 * Recycle bin: committed deletions resting for 30 days (when the bin
 * setting is on). Per-row restore and delete-forever, plus empty-bin.
 * Restore re-inserts into the system SMS provider when the app holds the
 * default-SMS role; otherwise the message comes back in-app only.
 */
@HiltViewModel
class BinViewModel
    @Inject
    constructor(
        private val messageRepository: MessageRepository,
        private val senderIdStore: SenderIdStore,
        private val contactsSource: ContactsSource,
        settings: SettingsRepository,
        @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
    ) : ViewModel() {
        private val displayCache = ConcurrentHashMap<String, SenderDisplay>()

        private val binEvents = Channel<BinEvent>(Channel.BUFFERED)
        val events: Flow<BinEvent> = binEvents.receiveAsFlow()

        val uiState: StateFlow<BinUiState> =
            combine(
                messageRepository.observeBin(),
                settings.showRichAvatars,
            ) { messages, richAvatars ->
                BinUiState(
                    items = messages.map { it.toInboxItem() },
                    richAvatars = richAvatars,
                    loaded = true,
                )
            }.flowOn(ioDispatcher)
                .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), BinUiState())

        fun restore(messageId: Long) {
            viewModelScope.launch(ioDispatcher) {
                val result = messageRepository.restoreFromBin(listOf(messageId))
                if (result.restored == 0) return@launch
                binEvents.send(if (result.fullyReinserted) BinEvent.Restored else BinEvent.RestoredAppOnly)
            }
        }

        /** Permanently removes one message - same effect as a hard delete today. */
        fun deleteForever(messageId: Long) {
            viewModelScope.launch(ioDispatcher) { messageRepository.deleteForever(listOf(messageId)) }
        }

        /** Permanently removes everything in the bin. */
        fun emptyBin() {
            viewModelScope.launch(ioDispatcher) {
                messageRepository.deleteForever(messageRepository.binMessageIds())
            }
        }

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
