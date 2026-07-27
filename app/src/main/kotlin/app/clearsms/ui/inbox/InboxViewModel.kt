package app.clearsms.ui.inbox

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.clearsms.data.db.MessageEntity
import app.clearsms.data.prefs.SettingsRepository
import app.clearsms.data.repository.MessageRepository
import app.clearsms.data.senderid.SenderIdStore
import app.clearsms.di.IoDispatcher
import app.clearsms.domain.model.Category
import app.clearsms.domain.model.SwipeAction
import app.clearsms.sms.ContactsSource
import app.clearsms.ui.components.BrandGlyph
import app.clearsms.ui.components.SenderDisplay
import app.clearsms.ui.components.brandGlyphFor
import app.clearsms.ui.components.resolveSenderDisplay
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Inbox filter: an optional single-select category chip plus an independent
 * "Unread" toggle that composes with any category (e.g. Important + Unread).
 */
data class InboxFilterState(
    val category: Category? = null,
    val unreadOnly: Boolean = false,
) {
    /** Selects [value], or clears the category when it is already selected. */
    fun selectCategory(value: Category): InboxFilterState = copy(category = if (category == value) null else value)

    fun toggleUnread(): InboxFilterState = copy(unreadOnly = !unreadOnly)
}

/** One inbox row: the latest message of a thread plus its resolved sender. */
data class InboxItem(
    val message: MessageEntity,
    val display: SenderDisplay,
    val glyph: BrandGlyph,
)

/** Most recent OTP eligible for the top banner. */
data class LatestOtp(
    val code: String,
    val senderName: String,
    val timestamp: Long,
)

data class InboxUiState(
    val items: List<InboxItem> = emptyList(),
    val filter: InboxFilterState = InboxFilterState(),
    val unreadCounts: Map<Category, Int> = emptyMap(),
    val totalUnread: Int = 0,
    val latestOtp: LatestOtp? = null,
    val isRefreshing: Boolean = false,
    val loaded: Boolean = false,
    val richAvatars: Boolean = true,
    val swipeStart: SwipeAction = SwipeAction.ARCHIVE,
    val swipeEnd: SwipeAction = SwipeAction.DELETE,
)

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class InboxViewModel
    @Inject
    constructor(
        private val messageRepository: MessageRepository,
        private val senderIdStore: SenderIdStore,
        private val contactsSource: ContactsSource,
        private val settings: SettingsRepository,
        @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
    ) : ViewModel() {
        private val filter = MutableStateFlow(InboxFilterState())
        private val refreshing = MutableStateFlow(false)

        /** Bumped when contacts become available so rows re-resolve names. */
        private val contactsTick = MutableStateFlow(0)

        init {
            // Honor the configured default filter on the first open of the
            // session; a user selection made in the meantime is never clobbered.
            viewModelScope.launch(ioDispatcher) {
                val startCategory = settings.defaultInboxFilter.first()
                filter.compareAndSet(InboxFilterState(), InboxFilterState(category = startCategory))
            }
        }

        private val items =
            combine(filter, contactsTick) { current, _ -> current }
                .flatMapLatest { current ->
                    messageRepository.observeInbox(current.category, current.unreadOnly)
                }.map { messages -> messages.map { it.toInboxItem() } }
                .flowOn(ioDispatcher)

        private val latestOtp =
            messageRepository
                .observeInbox(category = Category.OTP, unreadOnly = false)
                .map { messages ->
                    messages
                        .firstOrNull { it.extractedOtp != null && it.timestamp >= System.currentTimeMillis() - OTP_BANNER_WINDOW_MS }
                        ?.let { LatestOtp(it.extractedOtp!!, resolveDisplay(it.sender).name, it.timestamp) }
                }.flowOn(ioDispatcher)

        private data class Chrome(
            val richAvatars: Boolean,
            val swipeStart: SwipeAction,
            val swipeEnd: SwipeAction,
        )

        private val chrome =
            combine(
                settings.showRichAvatars,
                settings.swipeActionStart,
                settings.swipeActionEnd,
            ) { rich, start, end -> Chrome(rich, start, end) }

        val uiState: StateFlow<InboxUiState> =
            combine(
                items,
                filter,
                combine(messageRepository.observeUnreadCounts(), refreshing, ::Pair),
                latestOtp,
                chrome,
            ) { list, currentFilter, (counts, isRefreshing), otp, chromeState ->
                InboxUiState(
                    items = list,
                    filter = currentFilter,
                    unreadCounts = counts.associate { it.category to it.count },
                    totalUnread = counts.sumOf { it.count },
                    latestOtp = otp,
                    isRefreshing = isRefreshing,
                    loaded = true,
                    richAvatars = chromeState.richAvatars,
                    swipeStart = chromeState.swipeStart,
                    swipeEnd = chromeState.swipeEnd,
                )
            }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), InboxUiState())

        fun selectCategory(category: Category) {
            filter.update { it.selectCategory(category) }
        }

        fun toggleUnread() {
            filter.update { it.toggleUnread() }
        }

        /** READ_CONTACTS was just granted: drop stale lookups and re-resolve. */
        fun onContactsPermissionGranted() {
            contactsSource.invalidate()
            contactsTick.update { it + 1 }
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

        private fun MessageEntity.toInboxItem(): InboxItem {
            val display = resolveDisplay(sender)
            return InboxItem(
                message = this,
                display = display,
                glyph = brandGlyphFor(subCategory, display.name),
            )
        }

        private fun resolveDisplay(sender: String): SenderDisplay =
            resolveSenderDisplay(
                sender = sender,
                contactLookup = contactsSource::lookup,
                directoryLookup = { senderIdStore.lookup(it)?.name },
            )

        private companion object {
            /** OTPs older than this are no longer surfaced in the banner. */
            const val OTP_BANNER_WINDOW_MS = 15L * 60 * 1000
        }
    }
