package app.clearsms.ui.inbox

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingData
import androidx.paging.cachedIn
import androidx.paging.map
import androidx.work.WorkManager
import app.clearsms.data.db.InboxThreadRow
import app.clearsms.data.db.MessageEntity
import app.clearsms.data.prefs.SettingsRepository
import app.clearsms.data.repository.MessageRepository
import app.clearsms.data.repository.SenderBlocker
import app.clearsms.data.repository.UndoManager
import app.clearsms.data.senderid.SenderIdStore
import app.clearsms.di.IoDispatcher
import app.clearsms.domain.model.Category
import app.clearsms.domain.model.OtpDisplaySize
import app.clearsms.domain.model.SwipeAction
import app.clearsms.sms.ContactsSource
import app.clearsms.ui.common.RelativeTime
import app.clearsms.ui.common.UndoUiEvent
import app.clearsms.ui.components.BrandGlyph
import app.clearsms.ui.components.SelectionState
import app.clearsms.ui.components.SenderDisplay
import app.clearsms.ui.components.brandGlyphFor
import app.clearsms.ui.components.resolveSenderDisplay
import app.clearsms.work.CatchUpSyncScheduler
import app.clearsms.work.RecategorizeWorker
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap
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

    /**
     * Whether inbox rows should carry their category tag. Only views that mix
     * categories need it to disambiguate: no category pill selected (all
     * messages), with or without the Unread toggle. Under a single-category
     * pill every row would repeat the pill's own label, so the tag is hidden.
     */
    val showsCategoryTags: Boolean get() = category == null
}

/**
 * One inbox row: the latest message of a thread plus everything the row
 * needs precomputed (resolved sender, glyph, formatted time) so the item
 * composable does no per-frame work.
 */
data class InboxItem(
    val message: MessageEntity,
    val display: SenderDisplay,
    val glyph: BrandGlyph,
    val timeLabel: String,
    /**
     * The thread's unsent draft, or null. Shown as a "Draft: …" preview in
     * place of the last-message snippet; never affects unread state or sort.
     */
    val draftText: String? = null,
    /** Whether the thread is pinned (sorted above everything, pin glyph). */
    val pinned: Boolean = false,
)

/** Most recent OTP eligible for the top banner. */
data class LatestOtp(
    val code: String,
    val senderName: String,
    val timestamp: Long,
    /** Id of the source message: persisted when handled, and the highlight target on tap. */
    val messageId: Long,
    /** Thread the banner tap navigates into. */
    val threadId: Long,
)

data class InboxUiState(
    val filter: InboxFilterState = InboxFilterState(),
    val unreadCounts: Map<Category, Int> = emptyMap(),
    /** Pill order the user configured in Settings; empty means declaration order. */
    val pillOrder: List<Category> = emptyList(),
    val totalUnread: Int = 0,
    val latestOtp: LatestOtp? = null,
    val richAvatars: Boolean = true,
    val otpDisplaySize: OtpDisplaySize = OtpDisplaySize.DEFAULT,
    val swipeStart: SwipeAction = SwipeAction.ARCHIVE,
    val swipeEnd: SwipeAction = SwipeAction.DELETE,
    /** Automatic post-update re-sort in flight; null hides the banner. */
    val sortingBanner: SortingBanner? = null,
)

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class InboxViewModel
    @Inject
    constructor(
        private val messageRepository: MessageRepository,
        private val undoManager: UndoManager,
        private val senderBlocker: SenderBlocker,
        private val senderIdStore: SenderIdStore,
        private val contactsSource: ContactsSource,
        private val settings: SettingsRepository,
        private val catchUpSyncScheduler: CatchUpSyncScheduler,
        workManager: WorkManager,
        @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
    ) : ViewModel() {
        private val filter = MutableStateFlow(InboxFilterState())

        /** One-shot undo snackbar requests (delete/archive just staged). */
        private val undoEvents = Channel<UndoUiEvent>(Channel.BUFFERED)
        val undoEventFlow: Flow<UndoUiEvent> = undoEvents.receiveAsFlow()

        /** Bumped when contacts become available so rows re-resolve names. */
        private val contactsTick = MutableStateFlow(0)

        /** Sender → display cache so paged rows never repeat provider lookups. */
        private val displayCache = ConcurrentHashMap<String, SenderDisplay>()

        /** Multi-select over thread ids (inbox rows are threads). */
        private val selectionState = MutableStateFlow(SelectionState<Long>())
        val selection: StateFlow<SelectionState<Long>> = selectionState.asStateFlow()

        /**
         * True while EVERY selected thread is already pinned, so the bar's
         * pin entry can honestly read "Unpin" (mixed selections keep "Pin" -
         * see [SelectionBarLayout.isUnpin]). Queried per selection change
         * because select-all can cover threads no loaded page has seen.
         */
        val allSelectedPinned: StateFlow<Boolean> =
            selectionState
                .mapLatest { current ->
                    val ids = current.selected.toList()
                    SelectionBarLayout.isUnpin(
                        selectedCount = ids.size,
                        pinnedCount = if (ids.isEmpty()) 0 else messageRepository.pinnedCountInThreads(ids),
                    )
                }.flowOn(ioDispatcher)
                .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

        init {
            // Honor the configured default filter on the first open of the
            // session; a user selection made in the meantime is never clobbered.
            viewModelScope.launch(ioDispatcher) {
                val startCategory = settings.defaultInboxFilter.first()
                filter.compareAndSet(InboxFilterState(), InboxFilterState(category = startCategory))
            }
        }

        /**
         * Paged inbox rows: Room's PagingSource loads windows of
         * latest-per-thread messages instead of materializing the table, and
         * per-item work (sender resolution, glyph, time label) happens here
         * on the IO dispatcher - never during composition.
         */
        val pagedItems: Flow<PagingData<InboxItem>> =
            combine(filter, contactsTick) { current, _ -> current }
                .flatMapLatest { current ->
                    Pager(
                        config =
                            PagingConfig(
                                pageSize = PAGE_SIZE,
                                initialLoadSize = PAGE_SIZE * 2,
                                enablePlaceholders = false,
                            ),
                        pagingSourceFactory = { messageRepository.pagedInbox(current.category, current.unreadOnly) },
                    ).flow
                }.map { data -> data.map { it.toInboxItem() } }
                .flowOn(ioDispatcher)
                .cachedIn(viewModelScope)

        private val latestOtp =
            combine(
                messageRepository.observeInbox(category = Category.OTP, unreadOnly = false),
                settings.handledOtpMessageId,
            ) { messages, handledId ->
                OtpBannerPolicy
                    .select(messages, handledId, System.currentTimeMillis())
                    ?.let {
                        LatestOtp(
                            code = it.extractedOtp!!,
                            senderName = resolveDisplay(it.sender).name,
                            timestamp = it.timestamp,
                            messageId = it.id,
                            threadId = it.threadId,
                        )
                    }
            }.flowOn(ioDispatcher)

        /**
         * The AUTO-triggered re-sort's progress, observed straight from
         * WorkManager (the VM never owns the run). Manual sorts map to null
         * here by design - see [SortingBannerPolicy].
         */
        private val sortingBanner: Flow<SortingBanner?> =
            workManager
                .getWorkInfosForUniqueWorkFlow(RecategorizeWorker.WORK_NAME)
                .map { infos -> SortingBannerPolicy.select(infos) }

        private data class Chrome(
            val richAvatars: Boolean,
            val otpDisplaySize: OtpDisplaySize,
            val swipeStart: SwipeAction,
            val swipeEnd: SwipeAction,
            val pillOrder: List<Category>,
        )

        private val chrome =
            combine(
                settings.showRichAvatars,
                settings.otpDisplaySize,
                settings.swipeActionStart,
                settings.swipeActionEnd,
                settings.inboxPillOrder,
            ) { rich, otpSize, start, end, order -> Chrome(rich, otpSize, start, end, order) }

        val uiState: StateFlow<InboxUiState> =
            combine(
                filter,
                messageRepository.observeUnreadCounts(),
                latestOtp,
                chrome,
                sortingBanner,
            ) { currentFilter, counts, otp, chromeState, sorting ->
                InboxUiState(
                    filter = currentFilter,
                    unreadCounts = counts.associate { it.category to it.count },
                    totalUnread = counts.sumOf { it.count },
                    latestOtp = otp,
                    richAvatars = chromeState.richAvatars,
                    otpDisplaySize = chromeState.otpDisplaySize,
                    swipeStart = chromeState.swipeStart,
                    swipeEnd = chromeState.swipeEnd,
                    pillOrder = chromeState.pillOrder,
                    sortingBanner = sorting,
                )
            }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), InboxUiState())

        fun selectCategory(category: Category) {
            filter.update { it.selectCategory(category) }
        }

        /**
         * Persists the OTP as handled (copied or dismissed) so the banner
         * never shows it again - see [OtpBannerPolicy.select].
         */
        fun markOtpHandled(messageId: Long) {
            viewModelScope.launch(ioDispatcher) { settings.setHandledOtpMessageId(messageId) }
        }

        fun toggleUnread() {
            filter.update { it.toggleUnread() }
        }

        /** READ_CONTACTS was just granted: drop stale lookups and re-resolve. */
        fun onContactsPermissionGranted() {
            contactsSource.invalidate()
            displayCache.clear()
            contactsTick.update { it + 1 }
        }

        // Deliberately no refresh()/recategorize entry point here: the inbox
        // pull-to-refresh gesture was removed because a full inline
        // recategorization hung the UI. Settings → Sort inbox again runs the
        // same recategorization in a WorkManager worker with progress.

        fun markRead(
            messageId: Long,
            read: Boolean,
        ) {
            viewModelScope.launch(ioDispatcher) { messageRepository.markRead(messageId, read) }
        }

        fun archive(messageId: Long) {
            viewModelScope.launch(ioDispatcher) {
                undoManager.stageArchiveMessage(messageId)
                undoEvents.send(UndoUiEvent.Archived(1))
            }
        }

        fun delete(messageId: Long) {
            viewModelScope.launch(ioDispatcher) {
                val staged = undoManager.stageDeleteMessages(listOf(messageId))
                if (staged > 0) undoEvents.send(UndoUiEvent.Deleted(staged))
            }
        }

        /** Reverts the last delete/archive while its snackbar is showing. */
        fun undo() {
            viewModelScope.launch(ioDispatcher) { undoManager.undo() }
        }

        /**
         * Blocks [sender] through the SAME path Settings uses
         * ([SenderBlocker]): the sender lands in the Settings block-list
         * dialog (where unblocking lives), its existing conversation moves
         * to the recycle bin, and future messages arrive born-deleted and
         * silent. No confirm step - delete, the closest destructive
         * neighbor, has none either - and no undo snackbar (see
         * [SenderBlocker] for why); the bin keeps the messages restorable.
         */
        fun block(sender: String) {
            viewModelScope.launch(ioDispatcher) { senderBlocker.block(sender) }
        }

        /**
         * Forwards the inbox's default-SMS role checks (launch, resume,
         * role-dialog result) to the catch-up scheduler: a regained role or a
         * cold-start provider/local id gap enqueues the checkpointed history
         * import so messages that arrived while another app was default show
         * up, fully categorized, without duplicate rows or notifications.
         */
        fun onSmsRoleChecked(
            held: Boolean,
            regained: Boolean,
        ) {
            viewModelScope.launch(ioDispatcher) { catchUpSyncScheduler.onRoleChecked(held, regained) }
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

        /** Selects every thread in the current filtered view (queried, not just loaded pages). */
        fun selectAll() {
            viewModelScope.launch(ioDispatcher) {
                val current = filter.value
                val ids = messageRepository.inboxThreadIds(current.category, current.unreadOnly)
                selectionState.update { it.withAll(ids) }
            }
        }

        /** Deletes the selected threads undoably (staged; provider commit deferred). */
        fun deleteSelected() {
            val ids = selectionState.value.selected.toList()
            exitSelection()
            viewModelScope.launch(ioDispatcher) {
                val count = undoManager.stageDeleteThreads(ids)
                if (count > 0) undoEvents.send(UndoUiEvent.Deleted(count))
            }
        }

        fun archiveSelected() {
            val ids = selectionState.value.selected.toList()
            exitSelection()
            viewModelScope.launch(ioDispatcher) {
                val count = undoManager.stageArchiveThreads(ids)
                if (count > 0) undoEvents.send(UndoUiEvent.Archived(count))
            }
        }

        /** Marks read when anything selected is unread, otherwise marks unread. */
        fun toggleReadSelected() {
            val ids = selectionState.value.selected.toList()
            exitSelection()
            viewModelScope.launch(ioDispatcher) {
                val unread = messageRepository.unreadCountInThreads(ids)
                messageRepository.setReadForThreads(ids, read = unread > 0)
            }
        }

        /** Pins when anything selected is unpinned, otherwise unpins - same shape as [toggleReadSelected]. */
        fun togglePinSelected() {
            val ids = selectionState.value.selected.toList()
            exitSelection()
            viewModelScope.launch(ioDispatcher) {
                val pinnedCount = messageRepository.pinnedCountInThreads(ids)
                messageRepository.setPinned(ids, pinned = pinnedCount < ids.size)
            }
        }

        // endregion

        private fun InboxThreadRow.toInboxItem(): InboxItem {
            val display = resolveDisplay(message.sender)
            return InboxItem(
                message = message,
                display = display,
                glyph = brandGlyphFor(message.subCategory, display.name),
                timeLabel = RelativeTime.format(message.timestamp),
                draftText = draftText?.takeIf { it.isNotBlank() },
                pinned = pinned,
            )
        }

        private fun resolveDisplay(sender: String): SenderDisplay =
            displayCache.getOrPut(sender) {
                resolveSenderDisplay(
                    sender = sender,
                    contactLookup = contactsSource::lookup,
                    directoryLookup = { senderIdStore.lookup(it)?.name },
                )
            }

        private companion object {
            const val PAGE_SIZE = 40
        }
    }
