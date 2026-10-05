package app.clearsms.ui.conversation

import android.content.Context
import android.net.Uri
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingData
import androidx.paging.PagingSource
import androidx.paging.cachedIn
import androidx.paging.map
import app.clearsms.data.db.AttachmentDao
import app.clearsms.data.db.AttachmentEntity
import app.clearsms.data.db.DeliveryStatus
import app.clearsms.data.db.MessageEntity
import app.clearsms.data.prefs.SettingsRepository
import app.clearsms.data.repository.MessageRepository
import app.clearsms.data.repository.SenderMuter
import app.clearsms.data.repository.UndoManager
import app.clearsms.di.ApplicationScope
import app.clearsms.di.IoDispatcher
import app.clearsms.diagnostics.Diag
import app.clearsms.diagnostics.DiagField.Companion.count
import app.clearsms.diagnostics.DiagField.Companion.flag
import app.clearsms.diagnostics.DiagField.Companion.id
import app.clearsms.domain.categorizer.SenderIdLookup
import app.clearsms.domain.model.MessageSortOrder
import app.clearsms.domain.model.sortTimestamp
import app.clearsms.mms.DataSim
import app.clearsms.mms.DataSimHint
import app.clearsms.mms.MmsCapability
import app.clearsms.mms.MmsInbound
import app.clearsms.mms.MmsSender
import app.clearsms.mms.OutgoingAttachmentStager
import app.clearsms.mms.SendFailureReason
import app.clearsms.mms.StagedAttachment
import app.clearsms.notification.MutedSenderGate
import app.clearsms.notification.OtpClipboard
import app.clearsms.sms.ContactsSource
import app.clearsms.sms.SenderRepliability
import app.clearsms.sms.SimChoiceStore
import app.clearsms.sms.SimInfo
import app.clearsms.sms.SimSelector
import app.clearsms.sms.SmsSender
import app.clearsms.sms.SubscriptionSource
import app.clearsms.ui.common.AttachmentError
import app.clearsms.ui.common.ComposerAttachments
import app.clearsms.ui.common.RelativeTime
import app.clearsms.ui.common.ScheduleTipGate
import app.clearsms.ui.common.displayLocaleChanges
import app.clearsms.ui.common.UndoUiEvent
import app.clearsms.ui.components.BrandGlyph
import app.clearsms.ui.components.SelectionState
import app.clearsms.ui.components.SenderDisplay
import app.clearsms.ui.components.SimUiState
import app.clearsms.ui.components.brandGlyphFor
import app.clearsms.ui.components.resolveSenderDisplay
import app.clearsms.work.MessageScheduler
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject

/** One bubble in the conversation, mapped 1:1 from its persisted row. */
data class ConversationItem(
    val id: Long,
    val body: String,
    val timestamp: Long,
    /** Persisted direction ([MessageEntity.isOutgoing]) - drives alignment. */
    val outgoing: Boolean,
    /** Backing entity (category, OTP, archive state for the selection bar). */
    val message: MessageEntity? = null,
    /** Parsed extraction details (amount, bank, otp_code…) for the detail card. */
    val details: Map<String, String> = emptyMap(),
    /** Precomputed in the mapper so composition never formats dates. */
    val timeLabel: String = "",
    /** Persisted send lifecycle for outgoing messages (null on incoming). */
    val deliveryStatus: DeliveryStatus? = null,
    /** "SIM 1"/"SIM 2" provenance tag; null when tags are off or unknown. */
    val simLabel: String? = null,
    /**
     * For a FAILED outgoing MMS sent from a SIM other than the phone's
     * mobile-data SIM: the two slots the Retry dialog and details row name
     * in their hint. Null whenever the hint does not apply (see
     * [app.clearsms.mms.DataSim.hintFor]).
     */
    val dataSimHint: DataSimHint? = null,
)

/**
 * Maps a stored message to its bubble; direction and status come from the
 * row. [ConversationItem.timestamp] (and so the bubble's time label and the
 * date separators) is the instant the thread is SORTED by under
 * [sortOrder]: the received time by default, the sender's time when the
 * sent-time sort is on - so what the bubbles show is never out of order
 * with where they sit. A message with no known sent time keeps its
 * received time under either setting (see [MessageSortOrder.sortTimestamp]).
 */
internal fun MessageEntity.toConversationItem(
    json: Json,
    timeStrings: RelativeTime.Strings,
    simTagFor: (Int?) -> String? = { null },
    sortOrder: MessageSortOrder = MessageSortOrder.RECEIVED,
    dataSimHintFor: (MessageEntity) -> DataSimHint? = { null },
): ConversationItem {
    val shownAt = sortOrder.sortTimestamp(timestamp, dateSent)
    return ConversationItem(
        id = id,
        body = body,
        timestamp = shownAt,
        outgoing = isOutgoing,
        message = this,
        details = parseDetails(json, extractedDataJson),
        timeLabel = RelativeTime.format(shownAt, timeStrings),
        deliveryStatus = if (isOutgoing) deliveryStatus else null,
        simLabel = simTagFor(subscriptionId),
        dataSimHint = if (isOutgoing && deliveryStatus == DeliveryStatus.FAILED) dataSimHintFor(this) else null,
    )
}

private fun parseDetails(
    json: Json,
    raw: String?,
): Map<String, String> =
    raw?.let {
        try {
            json.decodeFromString(MapSerializer(String.serializer(), String.serializer()), it)
        } catch (_: Exception) {
            emptyMap()
        }
    } ?: emptyMap()

data class ConversationUiState(
    val title: String = "",
    val address: String = "",
    val photoUri: String? = null,
    val isKnownSender: Boolean = false,
    /**
     * The title came from the user's own address book. For a short code this
     * is the one thing the app CAN know about the user's intent: they saved
     * `80122` as "O2 Zusatzvolumen" because they correspond with it (GitHub
     * #75's screenshot was exactly that), so [repliable] opens the composer
     * for it outright. Verified on device: `PhoneLookup` resolves a saved
     * short code and nothing else resolves to it, see [app.clearsms.sms.ContactsSource].
     */
    val isContact: Boolean = false,
    /** Saved contact's lookup URI (name tap opens it); null for non-contacts. */
    val contactLookupUri: String? = null,
    val glyph: BrandGlyph = BrandGlyph.NONE,
    val richAvatars: Boolean = true,
    /**
     * What a reply to this sender can do (see [SenderRepliability]): a
     * subscriber number gets the composer, a short code gets a hedged notice
     * with "Reply anyway" unless it is a saved contact ([isContact]), an
     * alphanumeric id or no address gets a notice only. [repliable] is the
     * derived "show the composer" flag.
     */
    val repliability: SenderRepliability.Repliability = SenderRepliability.Repliability.INVALID,
    /**
     * The user tapped "Reply anyway" on a short code's notice: the composer
     * is shown for this screen session. Meaningless for other verdicts.
     */
    val replyAnyway: Boolean = false,
    /** Mirrors Settings -> Messages -> Show extracted message details (default OFF). */
    val showTransactionDetails: Boolean = false,
    /**
     * Whether this sender is muted (no notifications; messages still
     * arrive) - drives the overflow's Mute/Unmute toggle label.
     */
    val muted: Boolean = false,
    /**
     * The user's order of the selection-bar actions (Settings > Messages >
     * Message action order); [ConversationSelectionBarLayout] splits it
     * into inline and overflow for the current selection.
     */
    val selectionActionOrder: List<MessageSelectionAction> = ConversationSelectionBarLayout.defaultOrder,
    /**
     * Mirrors Settings -> Messages -> Recycle bin (default ON); picks the
     * delete dialog's wording via [app.clearsms.ui.common.DeleteConfirmationText].
     */
    val recycleBinEnabled: Boolean = true,
    /**
     * The device's CURRENT active subscriptions (empty on single-SIM-less
     * or permission-less devices), the same list the compose bar's SIM
     * indicator is built from. The "More details" dialog resolves a
     * message's stored subscription id against it for its SIM row, so the
     * dialog never reads a system service itself.
     */
    val activeSims: List<SimInfo> = emptyList(),
    val loaded: Boolean = false,
) {
    /**
     * Whether the composer is shown: a subscriber number outright, a short
     * code once the user chose "Reply anyway" OR when they have saved it as
     * a contact (a UI affordance only - what is addressable is still
     * [SenderRepliability]'s call, and an alphanumeric id stays closed even
     * when saved). Everything else gets the
     * [app.clearsms.ui.common.RepliabilityText] notice instead.
     */
    val repliable: Boolean
        get() =
            repliability == SenderRepliability.Repliability.NUMBER ||
                (repliability == SenderRepliability.Repliability.SHORT_CODE && (replyAnyway || isContact))
}

/** One-shot outcome of the overflow's mute toggle, surfaced as a snackbar. */
data class MuteToggled(
    val muted: Boolean,
)

/** One-shot send outcome consumed by the screen's snackbar. */
sealed interface SendEvent {
    /** The send resolved without a recorded failure - show "Message sent". */
    data object Sent : SendEvent

    /**
     * An MMS is still in the platform's hands after the result window: the
     * row is honestly SENDING and the platform may take minutes to report
     * (see [OutgoingSendPolicy]). The bar says so - it is backed by real
     * state, not a progress guess - and the real Sent / Not sent follows
     * when the result lands.
     */
    data object MmsInFlight : SendEvent

    /** The send failed; [messageId] identifies the row a Retry re-dispatches. */
    data class Failed(
        val messageId: Long,
    ) : SendEvent

    /**
     * A delayed send (GitHub #40) is pending: the message dispatches in
     * [delaySeconds] unless the snackbar's Cancel wins the race first.
     */
    data class Delayed(
        val messageId: Long,
        val delaySeconds: Int,
    ) : SendEvent
}

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class ConversationViewModel
    @Inject
    constructor(
        savedStateHandle: SavedStateHandle,
        private val messageRepository: MessageRepository,
        private val undoManager: UndoManager,
        private val senderIdLookup: SenderIdLookup,
        private val contactsSource: ContactsSource,
        private val smsSender: SmsSender,
        private val mmsSender: MmsSender,
        attachmentStager: OutgoingAttachmentStager,
        private val sentMessageWatcher: SentMessageWatcher,
        private val subscriptionSource: SubscriptionSource,
        private val simChoiceStore: SimChoiceStore,
        private val mmsCapability: MmsCapability,
        private val messageScheduler: MessageScheduler,
        private val scheduleTipGate: ScheduleTipGate,
        private val attachmentDao: AttachmentDao,
        private val mmsInbound: MmsInbound,
        private val settings: SettingsRepository,
        private val senderMuter: SenderMuter,
        private val json: Json,
        @ApplicationContext private val appContext: Context,
        @ApplicationScope private val applicationScope: CoroutineScope,
        @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
    ) : ViewModel() {
        private val threadId: Long = checkNotNull(savedStateHandle["threadId"])

        /**
         * Per-thread draft: restores the saved compose text on open and
         * persists edits, so leaving the thread (or process death) never
         * loses unsent text. Sending or scheduling consumes it.
         */
        private val conversationDraft =
            ConversationDraft(threadId, messageRepository, viewModelScope, ioDispatcher)
        val draft: StateFlow<String> = conversationDraft.text

        /** Compose-field edit; blank text clears the saved draft. */
        fun setDraft(value: String) = conversationDraft.set(value)

        /**
         * Compose-bar attachments (staged + compressed). Deliberately NOT
         * part of the persisted draft this wave: text survives leaving the
         * thread (as today), attachments do not - [onCleared] discards the
         * staged files.
         */
        private val composerAttachments =
            ComposerAttachments(attachmentStager, viewModelScope, ioDispatcher) { chosenSim.value }
        val stagedAttachments: StateFlow<List<StagedAttachment>> = composerAttachments.attachments
        val attachmentError: StateFlow<AttachmentError?> = composerAttachments.error

        /** The chosen SIM's attachment budget (carrier limit minus envelope margin) for the size line. */
        val attachmentBudgetBytes: StateFlow<Long> = composerAttachments.budgetBytes

        /** Adds picked/shared content as staged attachment chips. */
        fun addAttachments(uris: List<Uri>) = composerAttachments.add(uris)

        /** Removes an attachment chip (and its staged file). */
        fun removeAttachment(attachment: StagedAttachment) = composerAttachments.remove(attachment)

        /** Arms a camera capture; the result lands in [onCameraResult]. */
        fun cameraUri(): Uri = composerAttachments.cameraUri()

        fun onCameraResult(success: Boolean) = composerAttachments.onCameraResult(success)

        /**
         * Active SIMs, primed once in init; empty when none are available. A
         * StateFlow (not a bare field) so [uiState] can carry the list to
         * the "More details" dialog the moment it is known.
         */
        private val activeSimsFlow = MutableStateFlow<List<SimInfo>>(emptyList())
        private val activeSims: List<SimInfo> get() = activeSimsFlow.value

        /** Set by [replyAnyway]; read into [uiState]. */
        private val replyAnywayChosen = MutableStateFlow(false)

        /** Whether bubbles carry SIM tags (2+ SIMs on device or in corpus). */
        @Volatile
        private var simTagsEnabled: Boolean = false

        /** The phone's default DATA subscription, primed with [activeSims]; null when unknown. */
        @Volatile
        private var defaultDataSubscriptionId: Int? = null

        /** The recipient address, kept for the per-number SIM memory writes. */
        @Volatile
        private var recipientAddress: String = ""

        /** Subscription the next send will use; null = system default manager. */
        private val chosenSim = MutableStateFlow<Int?>(null)

        private val simUi = MutableStateFlow(SimUiState())
        val simState: StateFlow<SimUiState> = simUi.asStateFlow()

        /**
         * Whether the SIM that would send supports MMS at all. False only
         * when the carrier config explicitly disables it, in which case the
         * composer hides its attach button rather than offering a send that
         * can only be refused (issue #94). Starts true so the button is
         * never missing while the SIM list is still loading.
         */
        private val carrierMmsAvailable = MutableStateFlow(true)

        /**
         * The attach button is offered only when BOTH agree: the user has not
         * switched picture messages off in Settings, and the carrier config
         * does not declare MMS disabled for the sending SIM.
         */
        val mmsAvailableState: StateFlow<Boolean> =
            combine(carrierMmsAvailable, settings.mmsSendingEnabled) { carrier, enabled -> carrier && enabled }
                .stateIn(viewModelScope, SharingStarted.Eagerly, true)

        init {
            // Opening a conversation in-app means the user has now seen its
            // messages: the whole thread is marked read, and the repository
            // cancels every notification belonging to the now-read messages
            // (thread message notification, per-message transaction / OTP /
            // scam notifications, and any orphaned group summary). Only THIS
            // thread is touched; other conversations' notifications survive.
            viewModelScope.launch(ioDispatcher) {
                messageRepository.setReadForThreads(listOf(threadId), read = true)
            }
            // Bubble time labels are pre-formatted in the language of the
            // moment they were mapped; a language switch re-maps the loaded
            // pages in place (see activePagingSource).
            viewModelScope.launch {
                appContext.displayLocaleChanges().collect { activePagingSource?.invalidate() }
            }
            // Prime the SIM chooser: remembered per-recipient choice, else
            // the SIM this thread last used, else the system default.
            viewModelScope.launch(ioDispatcher) {
                activeSimsFlow.value = subscriptionSource.activeSims()
                defaultDataSubscriptionId = subscriptionSource.defaultDataSubscriptionId()
                simTagsEnabled =
                    SimSelector.showSimTags(activeSims, messageRepository.distinctSubscriptionIds())
                recipientAddress = messageRepository.firstInThread(threadId)?.sender.orEmpty()
                val remembered =
                    recipientAddress.takeIf { it.isNotBlank() }?.let { simChoiceStore.rememberedFor(it) }
                chosenSim.value =
                    SimSelector.choose(
                        activeSims = activeSims,
                        remembered = remembered,
                        lastUsedInThread = messageRepository.lastSubscriptionIdInThread(threadId),
                        defaultSubscriptionId = subscriptionSource.defaultSmsSubscriptionId(),
                    )
                refreshSimUi()
            }
        }

        /**
         * Cycles to the next SIM and remembers the choice for this recipient.
         * Returns the POST-switch UI state (what [simState] now shows), so the
         * tap toast can name the SIM that will actually send - the caller's
         * composition-captured state is one step behind. Null = no switch.
         */
        fun cycleSim(): SimUiState? {
            val next = SimSelector.next(activeSims, chosenSim.value) ?: return null
            chosenSim.value = next
            refreshSimUi()
            // The other SIM's carrier may allow a different MMS size.
            composerAttachments.refreshBudget()
            val address = recipientAddress
            if (address.isNotBlank()) {
                viewModelScope.launch(ioDispatcher) { simChoiceStore.remember(address, next) }
            }
            return simUi.value
        }

        private fun refreshSimUi() {
            val chosen = chosenSim.value
            val chosenInfo = activeSims.firstOrNull { it.subscriptionId == chosen }
            simUi.value =
                SimUiState(
                    visible = SimSelector.indicatorVisible(activeSims),
                    slot = SimSelector.slotNumberFor(activeSims, chosen) ?: 0,
                    simCount = activeSims.size,
                    operatorName = chosenInfo?.displayName.orEmpty(),
                    iconTint = chosenInfo?.iconTint,
                )
            // Re-read per SIM: on a dual-SIM phone one carrier can have MMS
            // while the other does not, so cycling the SIM can change the
            // answer (issue #94).
            carrierMmsAvailable.value = mmsCapability.isMmsAvailable(chosen)
        }

        /**
         * Read per row, not cached: the resources follow the app language, so a
         * row mapped after a language change is already in the new language -
         * and the loaded rows are re-mapped on that change (see
         * [activePagingSource]), so a bubble's label never outlives the
         * language it was formatted in.
         */
        private fun timeStrings(): RelativeTime.Strings = RelativeTime.Strings.from(appContext)

        /**
         * The PagingSource the running pager loads from, so a language change
         * can ask Paging for an in-place refresh (anchored, like Room's own
         * invalidation on a write) instead of rebuilding the pager and
         * jumping the list. Set from the factory on IO, read on main.
         */
        @Volatile
        private var activePagingSource: PagingSource<Int, MessageEntity>? = null

        /** Bubble SIM tag for a stored subscription id (null when tags are off). */
        private fun simTagFor(subscriptionId: Int?): String? =
            if (simTagsEnabled) SimSelector.slotLabelFor(activeSims, subscriptionId) else null

        /**
         * The "MMS may only work on the mobile-data SIM" hint for a failed
         * row, judged against the SIMs and data default as they are NOW: if
         * the user has since made the sending SIM the data SIM, a retry may
         * well work and the hint rightly disappears.
         */
        private fun dataSimHintFor(message: MessageEntity): DataSimHint? =
            DataSim.hintFor(
                reason = SendFailureReason.fromName(message.sendFailureReason),
                sendingSubscriptionId = message.subscriptionId,
                dataSubscriptionId = defaultDataSubscriptionId,
                activeSims = activeSims,
            )

        /**
         * Message to scroll to and briefly highlight, from search / Alerts /
         * Finance cards / notification taps; -1 (the nav default) means none.
         * Exposed as a plain property - NOT through [uiState] - because the
         * state flow combine is asynchronous: the screen's highlight effect
         * used to race it and silently miss the target on most opens.
         */
        val highlightTarget: Long? = highlightTargetOf(savedStateHandle.get<Long>("messageId"))

        /** One-shot send outcomes for the screen's snackbar. */
        private val sendEvents = Channel<SendEvent>(Channel.BUFFERED)
        val events: Flow<SendEvent> = sendEvents.receiveAsFlow()

        /** Fires once per install: the first send earns the long-press-to-schedule tip. */
        private val scheduleTipEvents = Channel<Unit>(Channel.BUFFERED)
        val scheduleTipFlow: Flow<Unit> = scheduleTipEvents.receiveAsFlow()

        /** One-shot undo snackbar requests (a delete was just staged). */
        private val undoEvents = Channel<UndoUiEvent>(Channel.BUFFERED)
        val undoEventFlow: Flow<UndoUiEvent> = undoEvents.receiveAsFlow()

        /** Fires after a reply is persisted so the screen pins back to the bottom. */
        private val scrollToBottomSignal = Channel<Unit>(Channel.CONFLATED)
        val scrollToBottom: Flow<Unit> = scrollToBottomSignal.receiveAsFlow()

        /** Multi-select over message ids within the thread. */
        private val selectionState = MutableStateFlow(SelectionState<Long>())
        val selection: StateFlow<SelectionState<Long>> = selectionState.asStateFlow()

        /**
         * Paged thread messages, newest first (the screen renders them with
         * `reverseLayout`), so a 14k-message thread only ever materializes the
         * visible window. Replies appear here too: sending persists the row
         * immediately, Room invalidates the pager, and the bubble renders
         * from its PERSISTED direction and status - it stays right-aligned
         * with its outcome after a restart, unlike the old session-state
         * bubbles. When navigation carries a highlight target, paging starts
         * at its position so the message is in the first load - a position
         * resolved under the SAME sort order the pager uses (GitHub #45), so
         * the jump lands on the target under either setting; a sort change
         * rebuilds the pager from a freshly resolved position.
         *
         * `distinctUntilChanged` because the DataStore-backed settings flow
         * re-emits its current value on ANY preference write; only a real
         * sort-order change may rebuild the pager (and jump the list).
         */
        val pagedItems: Flow<PagingData<ConversationItem>> =
            settings.messageSortOrder
                .distinctUntilChanged()
                .flatMapLatest { sortOrder ->
                    val position = initialPosition(sortOrder)
                    Pager(
                        config =
                            PagingConfig(
                                pageSize = PAGE_SIZE,
                                initialLoadSize = PAGE_SIZE * 2,
                                enablePlaceholders = false,
                            ),
                        initialKey = position,
                        pagingSourceFactory = {
                            messageRepository.pagedThread(threadId, sortOrder).also { activePagingSource = it }
                        },
                    ).flow
                        .map { data -> data.map { it.toConversationItem(json, timeStrings(), ::simTagFor, sortOrder, ::dataSimHintFor) } }
                }.flowOn(ioDispatcher)
                .cachedIn(viewModelScope)

        /**
         * The thread's MMS attachments keyed by message id. Kept beside the
         * paged items (not inside them) so paging never re-maps when an
         * attachment row lands; bubbles look their own list up by id.
         */
        val attachments: StateFlow<Map<Long, List<AttachmentEntity>>> =
            attachmentDao
                .observeForThread(threadId)
                .map { rows -> rows.groupBy { it.messageId } }
                .flowOn(ioDispatcher)
                .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyMap())

        /** Tapped "MMS could not be downloaded": flip to PENDING and re-fetch. */
        fun retryMmsDownload(messageId: Long) {
            viewModelScope.launch(ioDispatcher) { mmsInbound.retry(messageId) }
        }

        /** One-shot mute/unmute confirmations for the overflow toggle. */
        private val muteEvents = Channel<MuteToggled>(Channel.BUFFERED)
        val muteEventFlow: Flow<MuteToggled> = muteEvents.receiveAsFlow()

        /**
         * Flips the mute for this thread's sender through [SenderMuter] -
         * the same path the inbox and Settings use - and confirms it. A
         * refused mute (blocked sender) is unreachable from an open
         * conversation, since blocking bins the thread; it is not announced.
         */
        fun toggleMute() {
            val sender = uiState.value.address
            if (sender.isBlank()) return
            viewModelScope.launch(ioDispatcher) {
                senderMuter.toggle(sender)?.let { muteEvents.send(MuteToggled(muted = it)) }
            }
        }

        val uiState: StateFlow<ConversationUiState> =
            combine(
                flow { emit(messageRepository.firstInThread(threadId)) },
                settings.showRichAvatars,
                settings.showTransactionDetails,
                settings.mutedSenders,
                settings.messageSelectionActionOrder,
            ) { first, richAvatars, showDetails, mutedSenders, actionOrder ->
                val display = first?.sender?.let { resolveDisplay(it) }
                ConversationUiState(
                    title = display?.name.orEmpty(),
                    address = first?.sender.orEmpty(),
                    photoUri = display?.photoUri,
                    isKnownSender = display?.isKnownSender ?: false,
                    isContact = display?.isContact ?: false,
                    contactLookupUri = display?.contactLookupUri,
                    glyph = brandGlyphFor(first?.subCategory, display?.name.orEmpty()),
                    richAvatars = richAvatars,
                    repliability =
                        first?.sender?.let { SenderRepliability.classifyOnDevice(it) }
                            ?: SenderRepliability.Repliability.INVALID,
                    showTransactionDetails = showDetails,
                    muted = first?.sender?.let { MutedSenderGate.matches(mutedSenders, it) } ?: false,
                    selectionActionOrder = actionOrder,
                    loaded = first != null,
                )
            }
                // combine() maxes out at five flows; the bin setting rides a
                // second stage, like InboxViewModel's chrome chain.
                .combine(settings.recycleBinEnabled) { state, bin -> state.copy(recycleBinEnabled = bin) }
                // The SIM list the details dialog resolves provenance against.
                .combine(activeSimsFlow) { state, sims -> state.copy(activeSims = sims) }
                // "Reply anyway" on a short code's notice (GitHub #75).
                .combine(replyAnywayChosen) { state, anyway -> state.copy(replyAnyway = anyway) }
                .flowOn(ioDispatcher)
                .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ConversationUiState())

        /**
         * The user chose to reply to a short code despite the notice that it
         * may not accept replies: the composer opens for this screen
         * session (ViewModel-scoped, so it survives rotation). Only a
         * [SenderRepliability.Repliability.SHORT_CODE] sender has the
         * button, so nothing else can reach this.
         */
        fun replyAnyway() {
            replyAnywayChosen.value = true
        }

        /**
         * Dispatches [body]: with attachments staged the message goes out as
         * an MMS through [MmsSender], otherwise as an SMS through
         * [SmsSender]. Either way the outgoing row is persisted (Sending)
         * before dispatch, so the bubble appears immediately through paging
         * invalidation; its status and the snackbar [SendEvent] then resolve
         * from the persisted [DeliveryStatus].
         */
        fun send(body: String) {
            val destination = uiState.value.address
            val staged = composerAttachments.attachments.value
            if (destination.isBlank() || (body.isBlank() && staged.isEmpty())) return
            // Sending consumes the compose text AND the staged attachments:
            // the field and chips clear immediately; the bubble tracks the
            // send state.
            conversationDraft.consume()
            val attachments = composerAttachments.consume()
            viewModelScope.launch(ioDispatcher) {
                // Delayed sending (GitHub #40, opt-in via Settings): a plain
                // Send becomes a short SCHEDULE - the same durable
                // AlarmManager + SCHEDULED-row machinery as long-press
                // scheduling, so the message still goes out if the user
                // leaves the thread or the process dies mid-delay
                // (rearmAll fires overdue rows after a reboot). MMS bypasses
                // the delay: scheduling is SMS-only today (see
                // scheduleHintVisible), so attachments send immediately
                // rather than silently losing their files.
                if (attachments.isEmpty() && settings.delayedSendEnabled.first()) {
                    val delay = settings.delayedSendDelay.first()
                    val messageId =
                        try {
                            messageScheduler.schedule(
                                destination,
                                body,
                                chosenSim.value,
                                System.currentTimeMillis() + delay.millis,
                            )
                        } catch (e: Exception) {
                            // The user sees "Message not sent" with no row
                            // to tap: the report must say what threw.
                            Diag.e(TAG, "delayed send scheduling failed", e)
                            sendEvents.send(SendEvent.Failed(NO_MESSAGE))
                            return@launch
                        }
                    // Kept for cancel: the composer gets back EXACTLY what
                    // was typed, not the accent-folded wire body the
                    // scheduler persisted.
                    pendingDelayedOriginals[messageId] = body
                    scrollToBottomSignal.trySend(Unit)
                    sendEvents.send(SendEvent.Delayed(messageId, delay.seconds))
                    return@launch
                }
                val messageId =
                    try {
                        if (attachments.isEmpty()) {
                            smsSender.send(destination, body, chosenSim.value)
                        } else {
                            mmsSender.send(destination, body, attachments, chosenSim.value)
                        }
                    } catch (e: Exception) {
                        // Persisting the message itself failed - nothing to
                        // retry against, and the only trace is this line.
                        Diag.e(
                            TAG,
                            "send failed before a row existed",
                            e,
                            count("attachments", attachments.size),
                            flag(
                                "defaultSubscription",
                                chosenSim.value == null,
                            ),
                        )
                        sendEvents.send(SendEvent.Failed(NO_MESSAGE))
                        return@launch
                    }
                scrollToBottomSignal.trySend(Unit)
                if (scheduleTipGate.shouldShowTip()) scheduleTipEvents.send(Unit)
                resolve(messageId)
            }
        }

        /**
         * Re-dispatches a failed reply on its own row (bubble flips back to
         * Sending). A row with attachment rows retries through the MMS
         * path; everything else through SMS - the SAME tap->Retry dialog
         * serves both.
         */
        fun retry(messageId: Long) {
            if (messageId == NO_MESSAGE) return
            viewModelScope.launch(ioDispatcher) {
                try {
                    if (attachmentDao.forMessage(messageId).isNotEmpty()) {
                        mmsSender.resend(messageId)
                    } else {
                        smsSender.resend(messageId)
                    }
                } catch (e: Exception) {
                    Diag.e(TAG, "retry failed", e, id("message", messageId))
                    sendEvents.send(SendEvent.Failed(messageId))
                    return@launch
                }
                resolve(messageId)
            }
        }

        override fun onCleared() {
            // Attachment state does not persist in drafts this wave: the
            // staged files go with the screen. Deleted inline because the
            // ViewModel scope is already cancelled here.
            composerAttachments.consume().forEach { it.file.delete() }
            super.onCleared()
        }

        // region scheduled sends

        /**
         * Schedules [body] for [scheduledAtMs] instead of sending: the row
         * lands in the thread as a "scheduled" bubble (paging invalidation)
         * with the currently chosen SIM, and an alarm fires it later.
         */
        fun scheduleSend(
            body: String,
            scheduledAtMs: Long,
        ) {
            val destination = uiState.value.address
            if (destination.isBlank() || body.isBlank()) return
            // Scheduling is SMS-only this wave (see scheduleHintVisible);
            // the affordance is hidden with attachments staged, and this
            // guard keeps the invariant even if a caller slips through.
            if (composerAttachments.attachments.value.isNotEmpty()) return
            // Double-confirm protection, mirroring send's consumed-body
            // guard: the first confirm consumes the draft SYNCHRONOUSLY
            // below, so a second confirm carrying the same stale [body]
            // snapshot finds the draft already blank and is dropped - no
            // duplicate scheduled row can exist.
            if (conversationDraft.text.value.isBlank()) return
            // Scheduling consumes the compose text exactly like sending
            // does - no leftover draft next to the scheduled bubble.
            conversationDraft.consume()
            viewModelScope.launch(ioDispatcher) {
                messageScheduler.schedule(destination, body, chosenSim.value, scheduledAtMs)
                // Whoever schedules knows about long-press - never tip them.
                scheduleTipGate.markShown()
                scrollToBottomSignal.trySend(Unit)
            }
        }

        /** Moves a pending schedule to a new time. */
        fun editSchedule(
            messageId: Long,
            scheduledAtMs: Long,
        ) {
            viewModelScope.launch(ioDispatcher) { messageScheduler.reschedule(messageId, scheduledAtMs) }
        }

        /** Fires a pending schedule immediately; outcome via the send snackbar. */
        fun sendScheduledNow(messageId: Long) {
            viewModelScope.launch(ioDispatcher) {
                messageScheduler.sendNow(messageId)
                resolve(messageId)
            }
        }

        /** Cancels a pending schedule (bubble disappears; nothing was sent). */
        fun cancelSchedule(messageId: Long) {
            viewModelScope.launch(ioDispatcher) { messageScheduler.cancel(messageId) }
        }

        /**
         * The exact text the user typed for each still-pending delayed send,
         * so Cancel restores what was typed rather than the accent-folded
         * body the scheduler persisted. In-memory on purpose: the snackbar
         * (the only caller of [cancelDelayedSend]) dies with this ViewModel
         * anyway, and the message itself stays durable in Room regardless.
         */
        private val pendingDelayedOriginals = ConcurrentHashMap<Long, String>()

        /**
         * Cancel tapped on the delayed-send snackbar. Exactly one of two
         * outcomes, decided by the DAO's compare-and-set (never both):
         * - cancel won: the row is gone, nothing was ever sent, and the
         *   text goes BACK into the composer ready to edit - a cancel that
         *   discarded the text would be data loss;
         * - the fire won (the delay expired in the same instant): nothing
         *   is restored - the message is on its way, and an honest
         *   "Message sent" replaces the pending bar instead of a lie.
         */
        fun cancelDelayedSend(messageId: Long) {
            viewModelScope.launch(ioDispatcher) {
                val persistedBody = messageScheduler.cancelDelayed(messageId)
                val original = pendingDelayedOriginals.remove(messageId)
                if (persistedBody != null) {
                    conversationDraft.restore(original ?: persistedBody)
                } else {
                    sendEvents.send(SendEvent.Sent)
                }
            }
        }

        // endregion

        private suspend fun resolve(messageId: Long) {
            when (sentMessageWatcher.await(messageId)) {
                SendStatus.FAILED -> {
                    sendEvents.send(SendEvent.Failed(messageId))
                }

                SendStatus.SENT -> {
                    sendEvents.send(SendEvent.Sent)
                }

                SendStatus.SENDING -> {
                    // MMS with no result yet: say so, then keep watching the
                    // row until the platform's single result arrives and
                    // report THAT - never a Sent invented by the clock.
                    sendEvents.send(SendEvent.MmsInFlight)
                    val result = sentMessageWatcher.awaitResult(messageId)
                    sendEvents.send(
                        if (result == SendStatus.FAILED) SendEvent.Failed(messageId) else SendEvent.Sent,
                    )
                }
            }
        }

        fun delete(messageId: Long) {
            viewModelScope.launch(ioDispatcher) {
                val staged = undoManager.stageDeleteMessages(listOf(messageId))
                if (staged > 0) undoEvents.send(UndoUiEvent.Deleted(staged))
            }
        }

        /** Reverts the last staged delete while its snackbar is showing. */
        fun undo() {
            viewModelScope.launch(ioDispatcher) { undoManager.undo() }
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

        /** Deletes the selected messages undoably (staged; provider commit deferred). */
        fun deleteSelected() {
            val ids = selectionState.value.selected.toList()
            exitSelection()
            viewModelScope.launch(ioDispatcher) {
                val staged = undoManager.stageDeleteMessages(ids)
                if (staged > 0) undoEvents.send(UndoUiEvent.Deleted(staged))
            }
        }

        /**
         * Copies an extracted OTP through the app's single clipboard rule -
         * sensitive-flagged clip plus timed clear (see [OtpClipboard]), the
         * same code the notification's Copy action runs. The APPLICATION
         * scope keeps the 60s clear timer alive after the user leaves the
         * conversation; a screen-lived scope would cancel it on navigation.
         */
        fun copyOtp(otp: String) = OtpClipboard.copy(appContext, otp, applicationScope)

        /**
         * Concatenates the selected message bodies in chronological
         * (timestamp) order and hands the text to [onReady] on the main
         * thread. Serves copy (clipboard), share (chooser) and forward
         * (compose prefill) - one text-of-selection rule for all three.
         */
        fun selectedText(onReady: (String) -> Unit) {
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
                directoryLookup = { senderIdLookup.lookup(it)?.name },
            )

        private suspend fun initialPosition(sortOrder: MessageSortOrder): Int? =
            initialPagingKeyFor(
                highlightTarget?.let { messageRepository.positionInThread(threadId, it, sortOrder) },
            )

        private companion object {
            const val TAG = "Conversation"
            const val PAGE_SIZE = 60

            /** Sentinel for a send that failed before a row existed. */
            const val NO_MESSAGE = -1L
        }
    }
