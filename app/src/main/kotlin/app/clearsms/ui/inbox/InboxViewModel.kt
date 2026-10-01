package app.clearsms.ui.inbox

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingData
import androidx.paging.PagingSource
import androidx.paging.cachedIn
import androidx.paging.map
import androidx.work.WorkManager
import app.clearsms.data.db.InboxThreadRow
import app.clearsms.data.db.MessageEntity
import app.clearsms.data.prefs.SettingsRepository
import app.clearsms.data.repository.MessageRepository
import app.clearsms.data.repository.SenderBlocker
import app.clearsms.data.repository.SenderMuter
import app.clearsms.data.repository.UndoManager
import app.clearsms.di.IoDispatcher
import app.clearsms.domain.categorizer.SenderIdLookup
import app.clearsms.domain.model.Category
import app.clearsms.domain.model.InboxPill
import app.clearsms.domain.model.MessageSortOrder
import app.clearsms.domain.model.OtpDisplaySize
import app.clearsms.domain.model.SwipeAction
import app.clearsms.domain.model.SwipeDeadZone
import app.clearsms.domain.model.sortTimestamp
import app.clearsms.notification.MutedSenderGate
import app.clearsms.sms.ContactsSource
import app.clearsms.ui.common.RelativeTime
import app.clearsms.ui.common.UndoUiEvent
import app.clearsms.ui.components.BrandGlyph
import app.clearsms.ui.components.SelectionState
import app.clearsms.ui.components.SenderDisplay
import app.clearsms.ui.components.brandGlyphFor
import app.clearsms.ui.components.resolveSenderDisplay
import app.clearsms.ui.navigation.activePill
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
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
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
 * Inbox filter: an optional single-select pill plus an independent "Unread"
 * toggle that composes with any pill (e.g. Important + Unread).
 *
 * The pill is held by IDENTITY ([InboxPill]), never by its display label, so
 * a renamed pill filters exactly what it did before the rename.
 */
data class InboxFilterState(
    val pill: InboxPill? = null,
    val unreadOnly: Boolean = false,
) {
    /** The category the query filters on; null for All. */
    val category: Category? get() = pill?.category

    /** Selects [value], or clears the pill when it is already selected. */
    fun selectPill(value: InboxPill): InboxFilterState = copy(pill = if (pill == value) null else value)

    fun toggleUnread(): InboxFilterState = copy(unreadOnly = !unreadOnly)

    /**
     * The filter with every control the user has HIDDEN cleared: a hidden
     * pill has no chip to unselect it, so it must never stay active (a
     * default inbox filter pointing at a hidden category, or hiding the pill
     * that is currently selected); likewise an unread-only view cannot
     * persist once the Unread switch itself is hidden ([unreadControl]
     * false). Nothing else changes. The pill guard is the shared
     * [activePill], the same one Finance and Alerts apply; the Inbox's
     * unfiltered view is "no pill" (null).
     */
    fun constrainedTo(
        visible: Collection<InboxPill>,
        unreadControl: Boolean = true,
    ): InboxFilterState =
        copy(
            pill = activePill(pill, visible, fallback = null),
            unreadOnly = unreadOnly && unreadControl,
        )

    /**
     * Whether inbox rows should carry their category tag. Only views that mix
     * categories need it to disambiguate: no pill selected (all messages),
     * with or without the Unread toggle. Under a single-category pill every
     * row would repeat the pill's own label, so the tag is hidden.
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

/**
 * The inbox screen's state. [loaded] is the "settings read" flag (issue
 * #63): the screen's StateFlow has to start SOMEWHERE, and it starts on this
 * class's DEFAULTS - the built-in pill set, the Unread switch on, no counts.
 * Those are not the user's values, only Kotlin's, so until the first real
 * emission ([loaded] true) the screen must not render anything derived from
 * them - the same shape [app.clearsms.ui.conversation.ConversationUiState]
 * uses for its own not-yet-read state. Everything preference-derived below
 * is only meaningful once [loaded] is true.
 */
data class InboxUiState(
    val filter: InboxFilterState = InboxFilterState(),
    val unreadCounts: Map<Category, Int> = emptyMap(),
    /** Pill order, hidden set and labels the user configured in Settings. */
    val pills: InboxPillConfig = InboxPillConfig(),
    /** Whether the "Unread" switch above the pills is rendered at all. */
    val showUnreadToggle: Boolean = true,
    val totalUnread: Int = 0,
    val latestOtp: LatestOtp? = null,
    val richAvatars: Boolean = true,
    val otpDisplaySize: OtpDisplaySize = OtpDisplaySize.DEFAULT,
    val swipeStart: SwipeAction = SwipeAction.ARCHIVE,
    val swipeEnd: SwipeAction = SwipeAction.DELETE,
    /** Per-row band where a swipe never starts; off by default. */
    val swipeDeadZone: SwipeDeadZone = SwipeDeadZone.DEFAULT,
    /** Automatic post-update re-sort in flight; null hides the banner. */
    val sortingBanner: SortingBanner? = null,
    /**
     * Normalized muted-sender set, so each row can draw its muted-bell
     * glyph and the selection overflow can label its Mute/Unmute toggle.
     * Kept OUT of [InboxItem] on purpose: a mute toggle then re-renders the
     * rows in place instead of rebuilding the pager.
     */
    val mutedSenders: Set<String> = emptySet(),
    /**
     * Mirrors Settings -> Messages -> Recycle bin (default ON). Drives the
     * delete dialog's wording through [app.clearsms.ui.common.DeleteConfirmationText]:
     * with the bin on a delete is restorable, so the dialog must not call it
     * permanent.
     */
    val recycleBinEnabled: Boolean = true,
    /**
     * False only for the placeholder the StateFlow starts on, before the
     * first settings + counts emission; true on every real state. The
     * screen gates the pill row, the Unread switch and the list on it so a
     * cold start never shows a pill set or unread state the user did not
     * configure (issue #63).
     */
    val loaded: Boolean = false,
) {
    /** Whether [item]'s sender is muted (same normalization as the gate). */
    fun isMuted(item: InboxItem): Boolean = MutedSenderGate.matches(mutedSenders, item.message.sender)
}

/** One-shot outcome of a mute toggle, surfaced as a snackbar so the change is never silent. */
data class MuteUiEvent(
    val muted: Boolean,
)

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class InboxViewModel
    @Inject
    constructor(
        private val messageRepository: MessageRepository,
        private val undoManager: UndoManager,
        private val senderBlocker: SenderBlocker,
        private val senderMuter: SenderMuter,
        private val senderIdLookup: SenderIdLookup,
        private val contactsSource: ContactsSource,
        private val settings: SettingsRepository,
        private val catchUpSyncScheduler: CatchUpSyncScheduler,
        workManager: WorkManager,
        @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
    ) : ViewModel() {
        /**
         * The session's filter selection. Null until the default inbox filter
         * (a preference) has been READ: seeding it with `InboxFilterState()`
         * made the very first query and chip row an unfiltered "All" that
         * flipped to the configured default a moment later - the same
         * default-before-preference flash as the pills (issue #63). Nothing
         * downstream sees a filter until the real start value is in.
         */
        private val filter = MutableStateFlow<InboxFilterState?>(null)

        /** One-shot undo snackbar requests (delete/archive just staged). */
        private val undoEvents = Channel<UndoUiEvent>(Channel.BUFFERED)
        val undoEventFlow: Flow<UndoUiEvent> = undoEvents.receiveAsFlow()

        /** One-shot mute/unmute confirmations (the change must be visible, not just silent). */
        private val muteEvents = Channel<MuteUiEvent>(Channel.BUFFERED)
        val muteEventFlow: Flow<MuteUiEvent> = muteEvents.receiveAsFlow()

        /**
         * The PagingSource the running pager is currently loading from, so a
         * presentational change (contacts becoming available) can ask Paging
         * for an in-place refresh - the same anchored refresh Room triggers
         * on every write - instead of rebuilding the pager. Set from the
         * pager's factory on the IO dispatcher, read on the main thread.
         */
        @Volatile
        private var activePagingSource: PagingSource<Int, InboxThreadRow>? = null

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
            // session; a user selection made in the meantime is never clobbered
            // (only the not-yet-read null is replaced).
            viewModelScope.launch(ioDispatcher) {
                val startCategory = settings.defaultInboxFilter.first()
                filter.compareAndSet(null, InboxFilterState(pill = startCategory?.let(InboxPill::of)))
            }
        }

        /** The Settings-side pill customisation, resolved for rendering. */
        private val pillConfig: Flow<InboxPillConfig> =
            combine(settings.inboxPillOrder, settings.inboxHiddenPills, ::InboxPillConfig)

        /**
         * The filter every query and the chip row actually use: the user's
         * selection constrained to the VISIBLE pills, so a pill hidden in
         * Settings (or a default filter pointing at one) can never leave the
         * inbox filtered with no chip to clear it. The raw [filter] is kept
         * as chosen, so un-hiding the pill restores the selection.
         */
        private val effectiveFilter: Flow<InboxFilterState> =
            combine(filter.filterNotNull(), pillConfig, settings.inboxUnreadToggle) { current, config, unreadShown ->
                current.constrainedTo(config.visible, unreadControl = unreadShown)
            }.distinctUntilChanged()

        /**
         * What the pager is rebuilt from: ONLY the query inputs (see
         * [InboxPagerKey]). Everything presentational the rows also need -
         * unread counts, the OTP banner, chrome - lives in [uiState], which
         * the screen combines downstream; none of it can reach the
         * `flatMapLatest` below.
         */
        internal val pagerKeys: Flow<InboxPagerKey> = inboxPagerKeys(effectiveFilter, settings.messageSortOrder)

        /**
         * Paged inbox rows: Room's PagingSource loads windows of
         * latest-per-thread messages instead of materializing the table, and
         * per-item work (sender resolution, glyph, time label) happens here
         * on the IO dispatcher - never during composition.
         *
         * The row mapping MUST stay above `cachedIn`: `PagingData.map` on the
         * cached stream would drop the cached page event `LazyPagingItems`
         * seeds from, so every return to the screen would start from an
         * empty list and lose the scroll position.
         *
         * Placeholders are ON so the list's index space is the FULL thread
         * order, whatever window happens to be loaded. Room invalidates the
         * source on every write (opening a thread marks it read), and Paging
         * answers with a refresh anchored around the last accessed row - a
         * window that starts `initialLoadSize / 2` rows BEFORE that row.
         * Without placeholders that window is presented from index 0, so a
         * `LazyListState` restored to index N (saved state only holds an
         * index, never a key) is clamped to the window's end, or to 0 when
         * the refresh had no anchor to keep. With them, Room reports
         * `itemsBefore`/`itemsAfter`, index N still means the N-th thread,
         * and the rows around it load on access. Null rows render as
         * [InboxRowPlaceholder][app.clearsms.ui.inbox.InboxRowPlaceholder].
         */
        val pagedItems: Flow<PagingData<InboxItem>> =
            pagerKeys
                .flatMapLatest { key ->
                    Pager(
                        config =
                            PagingConfig(
                                pageSize = PAGE_SIZE,
                                initialLoadSize = PAGE_SIZE * 2,
                                enablePlaceholders = true,
                            ),
                        pagingSourceFactory = {
                            messageRepository
                                .pagedInbox(key.category, key.unreadOnly, key.sortOrder)
                                .also { activePagingSource = it }
                        },
                    ).flow
                        .map { data -> data.map { it.toInboxItem(key.sortOrder) } }
                }.flowOn(ioDispatcher)
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
            val pills: InboxPillConfig,
            /** Filled by the later combine stages (combine() maxes out at 5 flows). */
            val swipeDeadZone: SwipeDeadZone = SwipeDeadZone.DEFAULT,
            val showUnreadToggle: Boolean = true,
            val mutedSenders: Set<String> = emptySet(),
            val recycleBinEnabled: Boolean = true,
        )

        private val chrome =
            combine(
                settings.showRichAvatars,
                settings.otpDisplaySize,
                settings.swipeActionStart,
                settings.swipeActionEnd,
                pillConfig,
            ) { rich, otpSize, start, end, pills -> Chrome(rich, otpSize, start, end, pills) }
                .combine(settings.swipeDeadZone) { chrome, zone -> chrome.copy(swipeDeadZone = zone) }
                .combine(settings.inboxUnreadToggle) { chrome, shown -> chrome.copy(showUnreadToggle = shown) }
                .combine(settings.mutedSenders) { chrome, muted -> chrome.copy(mutedSenders = muted) }
                .combine(settings.recycleBinEnabled) { chrome, bin -> chrome.copy(recycleBinEnabled = bin) }

        val uiState: StateFlow<InboxUiState> =
            combine(
                effectiveFilter,
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
                    swipeDeadZone = chromeState.swipeDeadZone,
                    pills = chromeState.pills,
                    showUnreadToggle = chromeState.showUnreadToggle,
                    sortingBanner = sorting,
                    mutedSenders = chromeState.mutedSenders,
                    recycleBinEnabled = chromeState.recycleBinEnabled,
                    // Every input above has emitted at least once, so these
                    // are the user's values, not the defaults (issue #63).
                    loaded = true,
                )
            }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), InboxUiState())

        fun selectPill(pill: InboxPill) {
            // The chips are gated on the loaded state, so this cannot run on
            // the null placeholder; the fallback only keeps the type honest.
            filter.update { (it ?: InboxFilterState()).selectPill(pill) }
        }

        /**
         * Persists the OTP as handled (copied or dismissed) so the banner
         * never shows it again - see [OtpBannerPolicy.select].
         */
        fun markOtpHandled(messageId: Long) {
            viewModelScope.launch(ioDispatcher) { settings.setHandledOtpMessageId(messageId) }
        }

        fun toggleUnread() {
            filter.update { (it ?: InboxFilterState()).toggleUnread() }
        }

        /**
         * READ_CONTACTS was just granted: drop stale lookups and re-resolve.
         * Names are presentational, so this refreshes the loaded pages IN
         * PLACE (Paging reloads around the anchor and re-runs the row mapping)
         * rather than rebuilding the pager - the list stays where it is.
         */
        fun onContactsPermissionGranted() {
            contactsSource.invalidate()
            displayCache.clear()
            activePagingSource?.invalidate()
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
         * Mutes or unmutes [sender] through the SAME path Settings and the
         * conversation use ([SenderMuter]), then confirms with a snackbar
         * that also names what a mute covers (OTPs quiet, scam warnings
         * kept) - a silent mute is exactly the "did my messages vanish?"
         * confusion the unknown-sender channel taught us to avoid. Blocked
         * senders never reach the inbox, so a refused mute (null) cannot
         * happen here; it is simply not announced.
         */
        fun toggleMute(sender: String) {
            viewModelScope.launch(ioDispatcher) {
                senderMuter.toggle(sender)?.let { muteEvents.send(MuteUiEvent(muted = it)) }
            }
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
                val current = effectiveFilter.first()
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

        /** The row's time label shows the instant the list is sorted by. */
        private fun InboxThreadRow.toInboxItem(sortOrder: MessageSortOrder): InboxItem {
            val display = resolveDisplay(message.sender)
            return InboxItem(
                message = message,
                display = display,
                glyph = brandGlyphFor(message.subCategory, display.name),
                timeLabel = RelativeTime.format(sortOrder.sortTimestamp(message.timestamp, message.dateSent)),
                draftText = draftText?.takeIf { it.isNotBlank() },
                pinned = pinned,
            )
        }

        private fun resolveDisplay(sender: String): SenderDisplay =
            displayCache.getOrPut(sender) {
                resolveSenderDisplay(
                    sender = sender,
                    contactLookup = contactsSource::lookup,
                    directoryLookup = { senderIdLookup.lookup(it)?.name },
                )
            }

        private companion object {
            const val PAGE_SIZE = 40
        }
    }
