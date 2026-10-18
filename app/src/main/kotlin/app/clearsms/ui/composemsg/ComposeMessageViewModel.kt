package app.clearsms.ui.composemsg

import android.net.Uri
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.clearsms.data.db.MessageDao
import app.clearsms.data.prefs.SettingsRepository
import app.clearsms.di.IoDispatcher
import app.clearsms.diagnostics.Diag
import app.clearsms.diagnostics.DiagField.Companion.count
import app.clearsms.diagnostics.DiagField.Companion.flag
import app.clearsms.mms.MmsCapability
import app.clearsms.mms.MmsSender
import app.clearsms.mms.OutgoingAttachmentStager
import app.clearsms.mms.StagedAttachment
import app.clearsms.sms.SimChoiceStore
import app.clearsms.sms.SimInfo
import app.clearsms.sms.SimSelector
import app.clearsms.sms.ContactsSource
import app.clearsms.sms.ExistingThreadResolver
import app.clearsms.sms.OutgoingSendDispatcher
import app.clearsms.sms.SubscriptionSource
import app.clearsms.ui.common.AttachmentError
import app.clearsms.ui.common.ComposerAttachments
import app.clearsms.ui.common.ComposerHandOff
import app.clearsms.ui.common.ComposerHandOffs
import app.clearsms.ui.common.ScheduleTipGate
import app.clearsms.ui.components.SimUiState
import app.clearsms.ui.conversation.SendStatus
import app.clearsms.work.MessageScheduler
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicInteger
import javax.inject.Inject

data class ComposeUiState(
    val recipient: String = "",
    /** The chosen contact when the recipient came from a suggestion (name shown, number sent). */
    val picked: ContactSuggestion? = null,
    val body: String = "",
    val suggestions: List<ContactSuggestion> = emptyList(),
    /** Lifecycle of the current send; null before the first attempt. */
    val sendStatus: SendStatus? = null,
)

@OptIn(FlowPreview::class)
@HiltViewModel
class ComposeMessageViewModel
    @Inject
    constructor(
        savedStateHandle: SavedStateHandle,
        private val sendDispatcher: OutgoingSendDispatcher,
        private val mmsSender: MmsSender,
        attachmentStager: OutgoingAttachmentStager,
        private val messageDao: MessageDao,
        private val settings: SettingsRepository,
        private val existingThreads: ExistingThreadResolver,
        private val composerHandOffs: ComposerHandOffs,
        private val contactSuggestions: ContactSuggestions,
        private val contactsSource: ContactsSource,
        private val subscriptionSource: SubscriptionSource,
        private val simChoiceStore: SimChoiceStore,
        private val mmsCapability: MmsCapability,
        private val messageScheduler: MessageScheduler,
        private val scheduleTipGate: ScheduleTipGate,
        @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
    ) : ViewModel() {
        private val state =
            MutableStateFlow(
                ComposeUiState(
                    recipient = savedStateHandle.get<String>("recipient").orEmpty(),
                    body = savedStateHandle.get<String>("body").orEmpty(),
                ),
            )
        val uiState: StateFlow<ComposeUiState> = state.asStateFlow()

        /**
         * Compose attachments (staged + compressed). An inbound image share
         * arrives through the `imageUri` nav argument and is staged HERE,
         * immediately on open, because the share grant dies with the
         * activity - and it is never auto-sent: sending stays a user tap.
         */
        private val composerAttachments =
            ComposerAttachments(attachmentStager, viewModelScope, ioDispatcher) { chosenSim.value }
        val attachments: StateFlow<List<StagedAttachment>> = composerAttachments.attachments
        val attachmentError: StateFlow<AttachmentError?> = composerAttachments.error

        /** The chosen SIM's attachment budget (carrier limit minus envelope margin) for the size line. */
        val attachmentBudgetBytes: StateFlow<Long> = composerAttachments.budgetBytes

        /**
         * The MMS row a failed send left behind: Retry re-dispatches it via
         * [MmsSender.resend] instead of composing a duplicate.
         */
        private var failedMmsMessageId: Long? = null

        fun addAttachments(uris: List<Uri>) = composerAttachments.add(uris)

        fun removeAttachment(attachment: StagedAttachment) = composerAttachments.remove(attachment)

        fun cameraUri(): Uri = composerAttachments.cameraUri()

        fun onCameraResult(success: Boolean) = composerAttachments.onCameraResult(success)

        private val recipientQuery = MutableStateFlow("")

        /** Active SIMs, primed once in init; empty on single-SIM devices. */
        @Volatile
        private var activeSims: List<SimInfo> = emptyList()

        /** Subscription the next send will use; null = system default manager. */
        private val chosenSim = MutableStateFlow<Int?>(null)

        private val simUi = MutableStateFlow(SimUiState())
        val simState: StateFlow<SimUiState> = simUi.asStateFlow()

        /**
         * Whether the SIM that would send supports MMS at all - the same
         * veto the conversation screen applies, so the attach button cannot
         * appear in a new message while being hidden in an existing thread
         * (issue #94). Starts true: never missing while the SIM list loads.
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

        /** Fires once per install: the first send earns the long-press-to-schedule tip. */
        private val scheduleTipEvents = Channel<Unit>(Channel.BUFFERED)
        val scheduleTipFlow: Flow<Unit> = scheduleTipEvents.receiveAsFlow()

        /**
         * A successful dispatch created (or found) the thread: the screen
         * navigates into it, REPLACING this one, where the Sending (or
         * scheduled, or pending-with-Cancel) bubble is the send feedback.
         * Also the route a recipient this screen was OPENED with takes to
         * their existing conversation (issue #119): nothing to come back
         * to, so Back from there lands on the inbox.
         */
        private val openThreadEvents = Channel<Long>(Channel.BUFFERED)
        val openThreadFlow: Flow<Long> = openThreadEvents.receiveAsFlow()

        /**
         * A PICKED recipient already has a conversation (issue #119): the
         * screen pushes that conversation ON TOP of this one, with the typed
         * text and chips carried along (see [ComposerHandOff]). A push, not
         * a replacement, on purpose: a mis-tapped suggestion is one Back
         * away from this composer with everything still in it.
         */
        private val showThreadEvents = Channel<Long>(Channel.BUFFERED)
        val showThreadFlow: Flow<Long> = showThreadEvents.receiveAsFlow()

        /**
         * The inbound share's staging, awaited before a hand-off so the
         * shared image is in the chips it carries. Null when nothing was
         * shared in.
         */
        private var shareStagingJob: Job? = null

        /**
         * The in-flight per-recipient SIM lookup. Tracked so a manual
         * cycle tap can cancel it: without this, a lookup started by a
         * recipient edit could resume after the tap and silently overwrite
         * the user's explicit choice.
         */
        private var simRefreshJob: Job? = null

        /**
         * Generation counter for SIM decisions. Cancelling [simRefreshJob] is
         * NOT enough on its own: once a lookup has passed its last suspension
         * point (the per-recipient memory read), its tail runs to completion
         * even if cancellation arrives mid-tail, and would overwrite a choice
         * the user made in the meantime - the SIM visibly snapping back after
         * a tap. Each explicit cycle and each new lookup takes a generation;
         * a lookup only publishes its result while its generation is current.
         */
        private val simGeneration = AtomicInteger(0)

        init {
            // Prime the SIM chooser exactly like the conversation screen
            // does: the per-recipient memory decides once a recipient is
            // known, else the system default. No thread exists yet, so
            // there is no last-used-in-thread rung.
            simRefreshJob =
                viewModelScope.launch(ioDispatcher) {
                    activeSims = subscriptionSource.activeSims()
                    refreshSimForRecipient(simGeneration.incrementAndGet())
                }
            // An inbound image share: copy it into app staging NOW (the
            // URI grant is tied to the activity) as a removable chip.
            // Skipped entirely when the user has switched picture messages
            // off (issue #94): with no attach button, an image arriving by
            // share would otherwise be the one way left to start an MMS the
            // user has said they cannot send.
            savedStateHandle.get<String>("imageUri")?.takeIf { it.isNotBlank() }?.let { raw ->
                shareStagingJob =
                    viewModelScope.launch {
                        if (settings.mmsSendingEnabled.first()) {
                            composerAttachments.add(listOf(Uri.parse(raw)))?.join()
                        }
                    }
            }
            // A recipient this screen was OPENED with (an `sms:` link - the
            // contacts app's own "message" shortcut arrives this way, issue
            // #119) who already has a conversation goes straight to it, the
            // prefilled text and shared image carried along; the composer
            // is replaced, so Back lands on the inbox - there is no choice
            // here to undo. A recipient with no history stays in the
            // composer exactly as before.
            state.value.recipient.takeIf { it.isNotBlank() }?.let { address ->
                routeToExistingThread(address, replaceComposer = true)
            }
            // A Direct Share pick names a conversation, not an address: the
            // `threadId` argument is resolved to the thread's sender and set
            // as the recipient through the SAME path a picked suggestion
            // takes, so the SIM memory re-primes exactly as it would for a
            // typed number. The address comes from the thread's oldest live
            // message - the conversation screen's own header/reply address.
            // The address is then resolved to its saved contact through the
            // SAME lookup the conversation header uses ([ContactsSource]), so
            // the field shows the name with the number beneath and the
            // "change recipient" affordance - exactly what picking that
            // contact from the suggestions shows - instead of a bare number;
            // a non-contact stays a plain number. Only an EMPTY recipient is
            // filled: a route carrying both wins for the explicit recipient,
            // and a thread that no longer exists (binned since the share
            // sheet was opened) leaves the composer open with the shared
            // content and no recipient, never an error.
            savedStateHandle
                .get<Long>("threadId")
                ?.takeIf { it >= 0L && state.value.recipient.isBlank() }
                ?.let { threadId ->
                    viewModelScope.launch(ioDispatcher) {
                        val address = messageDao.firstInThread(threadId)?.sender?.trim().orEmpty()
                        if (address.isNotEmpty() && state.value.recipient.isBlank()) {
                            applySelection(resolvedSelection(address))
                            // The pick NAMED this conversation, and it has
                            // history by construction: go there (issue
                            // #119), shared content carried along.
                            handOff(threadId, address, replaceComposer = true)
                        }
                    }
                }
        }

        /**
         * Issue #119: a recipient who already has a conversation is taken to
         * it. The thread is the one a message to [address] WOULD JOIN - the
         * platform-thread-anchored identity every insert uses
         * ([ExistingThreadResolver]), never a raw number match that would
         * route a #42-merged person to the wrong place. No history: nothing
         * happens and the composer stays as it is.
         */
        private fun routeToExistingThread(
            address: String,
            replaceComposer: Boolean,
        ) {
            viewModelScope.launch(ioDispatcher) {
                val threadId = existingThreads.threadFor(address) ?: return@launch
                handOff(threadId, address, replaceComposer)
            }
        }

        /**
         * Carries what the user has here - typed text, staged chips, the
         * shared image once staged - into [threadId] and asks the screen to
         * show it. Dropped silently when the recipient changed while the
         * thread was being resolved: the hand-off would name the wrong
         * person. Replacing the composer MOVES the chips (nothing is left to
         * show them in); pushing over it copies them, so Back finds them.
         */
        private suspend fun handOff(
            threadId: Long,
            address: String,
            replaceComposer: Boolean,
        ) {
            shareStagingJob?.join()
            if (state.value.recipient.trim() != address.trim()) return
            val attachments =
                if (replaceComposer) composerAttachments.consume() else composerAttachments.attachments.value
            composerHandOffs.stash(
                threadId,
                ComposerHandOff(
                    draft = state.value.body,
                    attachments = attachments,
                    attachmentsOwned = replaceComposer,
                ),
            )
            if (replaceComposer) openThreadEvents.send(threadId) else showThreadEvents.send(threadId)
        }

        val suggestions: StateFlow<List<ContactSuggestion>> =
            contactSuggestionFeed(recipientQuery, contactSuggestions::search)
                .flowOn(ioDispatcher)
                .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

        fun onRecipientChange(value: String) {
            applySelection(currentSelection.edit(value))
            recipientQuery.value = value
        }

        fun pickSuggestion(suggestion: ContactSuggestion) {
            applySelection(currentSelection.pick(suggestion))
            recipientQuery.value = ""
            // Picked, not typed: a contact with history opens their
            // conversation (issue #119). Typing a number never does - a
            // half-typed number is not a choice.
            routeToExistingThread(state.value.recipient, replaceComposer = false)
        }

        /** Removes the picked contact and returns to manual entry. */
        fun clearPicked() {
            applySelection(currentSelection.clear())
            recipientQuery.value = ""
        }

        private val currentSelection: RecipientSelection
            get() = RecipientSelection(state.value.recipient, state.value.picked)

        /**
         * [address] as the selection a suggestion pick would have produced
         * when it belongs to a saved contact (name shown, this exact number
         * sent), or a plain edit when it does not. The lookup is the
         * conversation header's own; it answers null without READ_CONTACTS.
         */
        private fun resolvedSelection(address: String): RecipientSelection {
            val contact = contactsSource.lookup(address) ?: return currentSelection.edit(address)
            return currentSelection.pick(ContactSuggestion(name = contact.name, number = address, photoUri = contact.photoUri))
        }

        private fun applySelection(selection: RecipientSelection) {
            state.value = state.value.copy(recipient = selection.destination, picked = selection.picked)
            // The chosen recipient re-primes the SIM from the same
            // per-recipient memory the conversation screen writes.
            simRefreshJob?.cancel()
            val generation = simGeneration.incrementAndGet()
            simRefreshJob = viewModelScope.launch(ioDispatcher) { refreshSimForRecipient(generation) }
        }

        /**
         * Cycles to the next SIM and remembers the choice for this recipient.
         * Returns the POST-switch UI state (what [simState] now shows), so the
         * tap toast can name the SIM that will actually send - the caller's
         * composition-captured state is one step behind. Null = no switch.
         */
        fun cycleSim(): SimUiState? {
            // The user's explicit tap outranks any in-flight recipient lookup:
            // cancelling asks it to stop, and taking a new generation stops the
            // tail of one that already got past its last suspension point.
            simRefreshJob?.cancel()
            simGeneration.incrementAndGet()
            val next = SimSelector.next(activeSims, chosenSim.value) ?: return null
            chosenSim.value = next
            refreshSimUi()
            // The other SIM's carrier may allow a different MMS size.
            composerAttachments.refreshBudget()
            val address = state.value.recipient.trim()
            if (address.isNotBlank()) {
                viewModelScope.launch(ioDispatcher) { simChoiceStore.remember(address, next) }
            }
            return simUi.value
        }

        private suspend fun refreshSimForRecipient(generation: Int) {
            val address = state.value.recipient.trim()
            val remembered = address.takeIf { it.isNotBlank() }?.let { simChoiceStore.rememberedFor(it) }
            // A newer generation means the user cycled the SIM (or retyped the
            // recipient) while this lookup was in flight: its answer is stale.
            if (generation != simGeneration.get()) return
            chosenSim.value =
                SimSelector.choose(
                    activeSims = activeSims,
                    remembered = remembered,
                    lastUsedInThread = null,
                    defaultSubscriptionId = subscriptionSource.defaultSmsSubscriptionId(),
                )
            refreshSimUi()
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
            // Re-read per SIM: one carrier can have MMS while the other does
            // not, so changing the recipient (or cycling the SIM) can change
            // the answer.
            carrierMmsAvailable.value = mmsCapability.isMmsAvailable(chosen)
        }

        fun onBodyChange(value: String) {
            state.value = state.value.copy(body = value)
        }

        /**
         * Dispatches the message through [OutgoingSendDispatcher] - the ONE
         * send decision shared with the conversation screen (issue #119: the
         * delay used to be skipped here), so delayed sending applies to a
         * plain text exactly as it does there, and an MMS goes out at once.
         * The compose box is consumed OPTIMISTICALLY - body and attachment
         * chips clear the moment Send is tapped, exactly like the
         * conversation compose bar - and, once the outgoing row is persisted
         * (Sending, or Scheduled when delayed), the screen navigates INTO
         * the thread via [openThreadFlow]: the bubble there is the send
         * feedback, and a delayed send's Cancel bar is shown THERE (handed
         * over through [ComposerHandOff]), so no delayed snackbar is ever
         * the only response to the tap. Double-taps are idempotent twice
         * over: the synchronous SENDING guard and the already-consumed
         * (blank) body both drop the second tap. Only a dispatch failure
         * (nothing persisted, or an MMS row left behind to resend) keeps the
         * user here: the body is RESTORED and [ComposeUiState.sendStatus]
         * reports FAILED so Retry has something to retry. The chosen SIM
         * rides along exactly like a conversation reply.
         */
        fun send() {
            val current = state.value
            val staged = composerAttachments.attachments.value
            val retryMmsId = failedMmsMessageId
            val hasMms = staged.isNotEmpty() || retryMmsId != null
            if (current.recipient.isBlank() || (current.body.isBlank() && !hasMms)) return
            if (current.sendStatus == SendStatus.SENDING) return
            // Optimistic consume: the field and the chips empty NOW, before
            // dispatch - the immediate "did my tap register?" feedback.
            state.value = current.copy(body = "", sendStatus = SendStatus.SENDING)
            val attachments = if (staged.isNotEmpty()) composerAttachments.consume() else emptyList()
            viewModelScope.launch(ioDispatcher) {
                if (scheduleTipGate.shouldShowTip()) scheduleTipEvents.send(Unit)
                val outcome =
                    try {
                        if (retryMmsId != null && attachments.isEmpty()) {
                            // Attachments already live on the failed row;
                            // Retry re-dispatches THAT row.
                            mmsSender.resend(retryMmsId)
                            OutgoingSendDispatcher.Outcome.Dispatched(retryMmsId)
                        } else {
                            sendDispatcher
                                .send(current.recipient.trim(), signedBody(current.body), attachments, chosenSim.value)
                                .also { if (attachments.isNotEmpty()) failedMmsMessageId = it.messageId }
                        }
                    } catch (e: Exception) {
                        // Nothing (new) was persisted: give the text back so
                        // Retry is a real retry, not a blank no-op. The
                        // report gets the exception; the text and recipient
                        // stay in the composer only.
                        Diag.e(
                            TAG,
                            "compose send failed before a row existed",
                            e,
                            count("attachments", attachments.size),
                            flag(
                                "mmsRetry",
                                retryMmsId != null,
                            ),
                        )
                        state.value = state.value.copy(body = current.body, sendStatus = SendStatus.FAILED)
                        return@launch
                    }
                failedMmsMessageId = null
                // The thread exists with its Sending (or pending) bubble - go
                // there. The radio's Sent/Failed report resolves on that
                // bubble (and on the row's Retry affordance), not on this
                // screen; a delayed send's Cancel bar is shown there too.
                val threadId = messageDao.getById(outcome.messageId)?.threadId
                if (threadId != null) {
                    if (outcome is OutgoingSendDispatcher.Outcome.Delayed) {
                        composerHandOffs.stash(threadId, ComposerHandOff(delayedMessageId = outcome.messageId))
                    }
                    openThreadEvents.send(threadId)
                } else {
                    // Row vanished between persist and lookup (never in
                    // practice): fall back to the failure affordance.
                    state.value = state.value.copy(body = current.body, sendStatus = SendStatus.FAILED)
                }
            }
        }

        /**
         * Schedules the message instead of sending it, through the SAME
         * [MessageScheduler] the conversation screen uses: the thread is
         * created with a durable SCHEDULED row and an armed alarm - no
         * forked send path.
         *
         * The confirm mirrors [send] exactly: the compose box is consumed
         * OPTIMISTICALLY (the field empties the moment the picker is
         * confirmed), double-confirms are dropped twice over (the
         * synchronous SENDING guard and the already-consumed blank body),
         * and once the SCHEDULED row exists the screen navigates INTO the
         * (possibly new) thread via [openThreadFlow], where the
         * "Scheduled for <time>" bubble is the feedback. Only a persist
         * failure keeps the user here, with the body restored.
         */
        fun schedule(scheduledAtMs: Long) {
            val current = state.value
            if (current.recipient.isBlank() || current.body.isBlank()) return
            if (current.sendStatus == SendStatus.SENDING) return
            // Scheduling is SMS-only this wave (see scheduleHintVisible);
            // the affordance is hidden with attachments staged, and this
            // guard keeps the invariant even if a caller slips through.
            if (composerAttachments.attachments.value.isNotEmpty()) return
            // Optimistic consume, exactly like send: no duplicate scheduled
            // row can follow a double-confirm.
            state.value = current.copy(body = "", sendStatus = SendStatus.SENDING)
            viewModelScope.launch(ioDispatcher) {
                val messageId =
                    try {
                        messageScheduler.schedule(
                            destination = current.recipient.trim(),
                            body = signedBody(current.body),
                            subscriptionId = chosenSim.value,
                            scheduledAtMs = scheduledAtMs,
                        )
                    } catch (e: Exception) {
                        // Nothing was persisted: give the text back so the
                        // user can retry (send now, or long-press again).
                        Diag.e(TAG, "compose scheduling failed", e)
                        state.value = state.value.copy(body = current.body, sendStatus = SendStatus.FAILED)
                        return@launch
                    }
                // Whoever schedules knows about long-press - never tip them.
                scheduleTipGate.markShown()
                // The thread exists with its scheduled bubble - go there.
                val threadId = messageDao.getById(messageId)?.threadId
                if (threadId != null) {
                    openThreadEvents.send(threadId)
                } else {
                    // Row vanished between persist and lookup (never in
                    // practice): fall back to the failure affordance.
                    state.value = state.value.copy(body = current.body, sendStatus = SendStatus.FAILED)
                }
            }
        }

        /** The body with the configured signature appended, like every send. */
        private suspend fun signedBody(body: String): String {
            val signature = settings.signature.first()
            return if (signature.isNotBlank()) "$body\n$signature" else body
        }

        override fun onCleared() {
            // Attachment state does not persist in drafts this wave: the
            // staged files go with the screen. Deleted inline because the
            // ViewModel scope is already cancelled here.
            composerAttachments.consume().forEach { it.file.delete() }
            super.onCleared()
        }

        private companion object {
            const val TAG = "Compose"
        }
    }
