package app.clearsms.ui.conversation

import android.net.Uri
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingData
import androidx.paging.cachedIn
import androidx.paging.map
import app.clearsms.data.db.AttachmentDao
import app.clearsms.data.db.AttachmentEntity
import app.clearsms.data.db.DeliveryStatus
import app.clearsms.data.db.MessageEntity
import app.clearsms.data.prefs.SettingsRepository
import app.clearsms.data.repository.MessageRepository
import app.clearsms.data.repository.UndoManager
import app.clearsms.data.senderid.SenderIdStore
import app.clearsms.di.IoDispatcher
import app.clearsms.mms.MmsInbound
import app.clearsms.mms.MmsSender
import app.clearsms.mms.OutgoingAttachmentStager
import app.clearsms.mms.StagedAttachment
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
import app.clearsms.ui.common.UndoUiEvent
import app.clearsms.ui.components.BrandGlyph
import app.clearsms.ui.components.SelectionState
import app.clearsms.ui.components.SenderDisplay
import app.clearsms.ui.components.SimUiState
import app.clearsms.ui.components.brandGlyphFor
import app.clearsms.ui.components.resolveSenderDisplay
import app.clearsms.work.MessageScheduler
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
)

/** Maps a stored message to its bubble; direction and status come from the row. */
internal fun MessageEntity.toConversationItem(
    json: Json,
    simTagFor: (Int?) -> String? = { null },
): ConversationItem =
    ConversationItem(
        id = id,
        body = body,
        timestamp = timestamp,
        outgoing = isOutgoing,
        message = this,
        details = parseDetails(json, extractedDataJson),
        timeLabel = RelativeTime.format(timestamp),
        deliveryStatus = if (isOutgoing) deliveryStatus else null,
        simLabel = simTagFor(subscriptionId),
    )

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
    val glyph: BrandGlyph = BrandGlyph.NONE,
    val richAvatars: Boolean = true,
    /** False for one-way senders (alphanumeric ids, short codes): composer is hidden. */
    val repliable: Boolean = false,
    /** Mirrors Settings → Appearance → Show extracted message details. */
    val showTransactionDetails: Boolean = true,
    val loaded: Boolean = false,
)

/** One-shot send outcome consumed by the screen's snackbar. */
sealed interface SendEvent {
    /** The send resolved without a recorded failure - show "Message sent". */
    data object Sent : SendEvent

    /** The send failed; [messageId] identifies the row a Retry re-dispatches. */
    data class Failed(
        val messageId: Long,
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
        private val senderIdStore: SenderIdStore,
        private val contactsSource: ContactsSource,
        private val smsSender: SmsSender,
        private val mmsSender: MmsSender,
        attachmentStager: OutgoingAttachmentStager,
        private val sentMessageWatcher: SentMessageWatcher,
        private val subscriptionSource: SubscriptionSource,
        private val simChoiceStore: SimChoiceStore,
        private val messageScheduler: MessageScheduler,
        private val scheduleTipGate: ScheduleTipGate,
        private val attachmentDao: AttachmentDao,
        private val mmsInbound: MmsInbound,
        settings: SettingsRepository,
        private val json: Json,
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
            ComposerAttachments(attachmentStager, viewModelScope, ioDispatcher)
        val stagedAttachments: StateFlow<List<StagedAttachment>> = composerAttachments.attachments
        val attachmentError: StateFlow<AttachmentError?> = composerAttachments.error

        /** Adds picked/shared content as staged attachment chips. */
        fun addAttachments(uris: List<Uri>) = composerAttachments.add(uris)

        /** Removes an attachment chip (and its staged file). */
        fun removeAttachment(attachment: StagedAttachment) = composerAttachments.remove(attachment)

        /** Arms a camera capture; the result lands in [onCameraResult]. */
        fun cameraUri(): Uri = composerAttachments.cameraUri()

        fun onCameraResult(success: Boolean) = composerAttachments.onCameraResult(success)

        /** Active SIMs, primed once in init; empty on single-SIM devices. */
        @Volatile
        private var activeSims: List<SimInfo> = emptyList()

        /** Whether bubbles carry SIM tags (2+ SIMs on device or in corpus). */
        @Volatile
        private var simTagsEnabled: Boolean = false

        /** The recipient address, kept for the per-number SIM memory writes. */
        @Volatile
        private var recipientAddress: String = ""

        /** Subscription the next send will use; null = system default manager. */
        private val chosenSim = MutableStateFlow<Int?>(null)

        private val simUi = MutableStateFlow(SimUiState())
        val simState: StateFlow<SimUiState> = simUi.asStateFlow()

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
            // Prime the SIM chooser: remembered per-recipient choice, else
            // the SIM this thread last used, else the system default.
            viewModelScope.launch(ioDispatcher) {
                activeSims = subscriptionSource.activeSims()
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

        /** Cycles to the next SIM and remembers the choice for this recipient. */
        fun cycleSim() {
            val next = SimSelector.next(activeSims, chosenSim.value) ?: return
            chosenSim.value = next
            refreshSimUi()
            val address = recipientAddress
            if (address.isNotBlank()) {
                viewModelScope.launch(ioDispatcher) { simChoiceStore.remember(address, next) }
            }
        }

        private fun refreshSimUi() {
            val chosen = chosenSim.value
            simUi.value =
                SimUiState(
                    visible = SimSelector.indicatorVisible(activeSims),
                    slot = SimSelector.slotNumberFor(activeSims, chosen) ?: 0,
                    simCount = activeSims.size,
                    operatorName = activeSims.firstOrNull { it.subscriptionId == chosen }?.displayName.orEmpty(),
                )
        }

        /** Bubble SIM tag for a stored subscription id (null when tags are off). */
        private fun simTagFor(subscriptionId: Int?): String? =
            if (simTagsEnabled) SimSelector.slotLabelFor(activeSims, subscriptionId) else null

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
         * at its position so the message is in the first load.
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
                }.map { data -> data.map { it.toConversationItem(json, ::simTagFor) } }
                .flowOn(ioDispatcher)
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

        val uiState: StateFlow<ConversationUiState> =
            combine(
                flow { emit(messageRepository.firstInThread(threadId)) },
                settings.showRichAvatars,
                settings.showTransactionDetails,
            ) { first, richAvatars, showDetails ->
                val display = first?.sender?.let { resolveDisplay(it) }
                ConversationUiState(
                    title = display?.name.orEmpty(),
                    address = first?.sender.orEmpty(),
                    photoUri = display?.photoUri,
                    isKnownSender = display?.isKnownSender ?: false,
                    glyph = brandGlyphFor(first?.subCategory, display?.name.orEmpty()),
                    richAvatars = richAvatars,
                    repliable = first?.sender?.let { SenderRepliability.isRepliable(it) } ?: false,
                    showTransactionDetails = showDetails,
                    loaded = first != null,
                )
            }.flowOn(ioDispatcher)
                .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ConversationUiState())

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
                val messageId =
                    try {
                        if (attachments.isEmpty()) {
                            smsSender.send(destination, body, chosenSim.value)
                        } else {
                            mmsSender.send(destination, body, attachments, chosenSim.value)
                        }
                    } catch (_: Exception) {
                        // Persisting the message itself failed - nothing to retry against.
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
                } catch (_: Exception) {
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

        // endregion

        private suspend fun resolve(messageId: Long) {
            val status = sentMessageWatcher.await(messageId)
            sendEvents.send(
                if (status == SendStatus.FAILED) SendEvent.Failed(messageId) else SendEvent.Sent,
            )
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
                directoryLookup = { senderIdStore.lookup(it)?.name },
            )

        private suspend fun initialPosition(): Int? =
            highlightTarget
                ?.let { messageRepository.positionInThread(threadId, it) }
                ?.takeIf { it > 0 }

        private companion object {
            const val PAGE_SIZE = 60

            /** Sentinel for a send that failed before a row existed. */
            const val NO_MESSAGE = -1L
        }
    }
