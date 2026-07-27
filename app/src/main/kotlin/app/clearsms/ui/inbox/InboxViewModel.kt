package app.clearsms.ui.inbox

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.clearsms.data.db.MessageEntity
import app.clearsms.data.repository.MessageRepository
import app.clearsms.data.senderid.SenderIdStore
import app.clearsms.di.IoDispatcher
import app.clearsms.domain.model.Category
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/** Inbox filter selection: a category chip or the unread chip. */
sealed interface InboxFilter {
    data object All : InboxFilter

    data class ByCategory(
        val category: Category,
    ) : InboxFilter

    data object Unread : InboxFilter
}

/** One inbox row: the latest message of a thread plus its resolved sender name. */
data class InboxItem(
    val message: MessageEntity,
    val displayName: String,
)

/** Most recent OTP eligible for the top banner. */
data class LatestOtp(
    val code: String,
    val senderName: String,
    val timestamp: Long,
)

data class InboxUiState(
    val items: List<InboxItem> = emptyList(),
    val filter: InboxFilter = InboxFilter.All,
    val unreadCounts: Map<Category, Int> = emptyMap(),
    val totalUnread: Int = 0,
    val latestOtp: LatestOtp? = null,
    val isRefreshing: Boolean = false,
    val loaded: Boolean = false,
)

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class InboxViewModel
    @Inject
    constructor(
        private val messageRepository: MessageRepository,
        private val senderIdStore: SenderIdStore,
        @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
    ) : ViewModel() {
        private val filter = MutableStateFlow<InboxFilter>(InboxFilter.All)
        private val refreshing = MutableStateFlow(false)

        private val items =
            filter
                .flatMapLatest { current ->
                    when (current) {
                        InboxFilter.All -> messageRepository.observeInbox(category = null, unreadOnly = false)
                        is InboxFilter.ByCategory -> messageRepository.observeInbox(current.category, unreadOnly = false)
                        InboxFilter.Unread -> messageRepository.observeInbox(category = null, unreadOnly = true)
                    }
                }.map { messages ->
                    messages.map { InboxItem(it, resolveName(it.sender)) }
                }.flowOn(ioDispatcher)

        private val latestOtp =
            messageRepository
                .observeInbox(category = Category.OTP, unreadOnly = false)
                .map { messages ->
                    messages
                        .firstOrNull { it.extractedOtp != null && it.timestamp >= System.currentTimeMillis() - OTP_BANNER_WINDOW_MS }
                        ?.let { LatestOtp(it.extractedOtp!!, resolveName(it.sender), it.timestamp) }
                }.flowOn(ioDispatcher)

        val uiState: StateFlow<InboxUiState> =
            combine(
                items,
                filter,
                messageRepository.observeUnreadCounts(),
                latestOtp,
                refreshing,
            ) { list, currentFilter, counts, otp, isRefreshing ->
                InboxUiState(
                    items = list,
                    filter = currentFilter,
                    unreadCounts = counts.associate { it.category to it.count },
                    totalUnread = counts.sumOf { it.count },
                    latestOtp = otp,
                    isRefreshing = isRefreshing,
                    loaded = true,
                )
            }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), InboxUiState())

        fun selectFilter(newFilter: InboxFilter) {
            filter.value = if (filter.value == newFilter) InboxFilter.All else newFilter
        }

        fun refresh() {
            viewModelScope.launch(ioDispatcher) {
                refreshing.value = true
                try {
                    messageRepository.recategorizeAll()
                } finally {
                    refreshing.value = false
                }
            }
        }

        fun markRead(
            messageId: Long,
            read: Boolean,
        ) {
            viewModelScope.launch(ioDispatcher) { messageRepository.markRead(messageId, read) }
        }

        fun archive(messageId: Long) {
            viewModelScope.launch(ioDispatcher) { messageRepository.archive(messageId) }
        }

        fun delete(messageId: Long) {
            viewModelScope.launch(ioDispatcher) { messageRepository.delete(messageId) }
        }

        fun block(sender: String) {
            viewModelScope.launch(ioDispatcher) { messageRepository.setBlocked(sender, true) }
        }

        private fun resolveName(sender: String): String = senderIdStore.lookup(sender)?.name ?: sender

        private companion object {
            /** OTPs older than this are no longer surfaced in the banner. */
            const val OTP_BANNER_WINDOW_MS = 15L * 60 * 1000
        }
    }
