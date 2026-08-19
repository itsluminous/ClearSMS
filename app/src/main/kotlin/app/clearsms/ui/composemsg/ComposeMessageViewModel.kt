package app.clearsms.ui.composemsg

import android.net.Uri
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.clearsms.data.db.MessageDao
import app.clearsms.data.prefs.SettingsRepository
import app.clearsms.di.IoDispatcher
import app.clearsms.mms.MmsSender
import app.clearsms.mms.OutgoingAttachmentStager
import app.clearsms.mms.StagedAttachment
import app.clearsms.sms.SimChoiceStore
import app.clearsms.sms.SimInfo
import app.clearsms.sms.SimSelector
import app.clearsms.sms.SmsSender
import app.clearsms.sms.SubscriptionSource
import app.clearsms.ui.common.AttachmentError
import app.clearsms.ui.common.ComposerAttachments
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
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
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
        private val smsSender: SmsSender,
        private val mmsSender: MmsSender,
        attachmentStager: OutgoingAttachmentStager,
        private val messageDao: MessageDao,
        private val settings: SettingsRepository,
        private val contactSuggestions: ContactSuggestions,
        private val subscriptionSource: SubscriptionSource,
        private val simChoiceStore: SimChoiceStore,
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
            ComposerAttachments(attachmentStager, viewModelScope, ioDispatcher)
        val attachments: StateFlow<List<StagedAttachment>> = composerAttachments.attachments
        val attachmentError: StateFlow<AttachmentError?> = composerAttachments.error

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

        /** Fires once per install: the first send earns the long-press-to-schedule tip. */
        private val scheduleTipEvents = Channel<Unit>(Channel.BUFFERED)
        val scheduleTipFlow: Flow<Unit> = scheduleTipEvents.receiveAsFlow()

        /**
         * A successful dispatch created (or found) the thread: the screen
         * navigates into it, where the Sending bubble is the send feedback.
         */
        private val openThreadEvents = Channel<Long>(Channel.BUFFERED)
        val openThreadFlow: Flow<Long> = openThreadEvents.receiveAsFlow()

        /**
         * The in-flight per-recipient SIM lookup. Tracked so a manual
         * cycle tap can cancel it: without this, a lookup started by a
         * recipient edit could resume after the tap and silently overwrite
         * the user's explicit choice.
         */
        private var simRefreshJob: Job? = null

        init {
            // Prime the SIM chooser exactly like the conversation screen
            // does: the per-recipient memory decides once a recipient is
            // known, else the system default. No thread exists yet, so
            // there is no last-used-in-thread rung.
            simRefreshJob =
                viewModelScope.launch(ioDispatcher) {
                    activeSims = subscriptionSource.activeSims()
                    refreshSimForRecipient()
                }
            // An inbound image share: copy it into app staging NOW (the
            // URI grant is tied to the activity) as a removable chip.
            savedStateHandle.get<String>("imageUri")?.takeIf { it.isNotBlank() }?.let { raw ->
                composerAttachments.add(listOf(Uri.parse(raw)))
            }
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
        }

        /** Removes the picked contact and returns to manual entry. */
        fun clearPicked() {
            applySelection(currentSelection.clear())
            recipientQuery.value = ""
        }

        private val currentSelection: RecipientSelection
            get() = RecipientSelection(state.value.recipient, state.value.picked)

        private fun applySelection(selection: RecipientSelection) {
            state.value = state.value.copy(recipient = selection.destination, picked = selection.picked)
            // The chosen recipient re-primes the SIM from the same
            // per-recipient memory the conversation screen writes.
            simRefreshJob?.cancel()
            simRefreshJob = viewModelScope.launch(ioDispatcher) { refreshSimForRecipient() }
        }

        /** Cycles to the next SIM and remembers the choice for this recipient. */
        fun cycleSim() {
            // The user's explicit tap outranks any in-flight recipient lookup.
            simRefreshJob?.cancel()
            val next = SimSelector.next(activeSims, chosenSim.value) ?: return
            chosenSim.value = next
            refreshSimUi()
            val address = state.value.recipient.trim()
            if (address.isNotBlank()) {
                viewModelScope.launch(ioDispatcher) { simChoiceStore.remember(address, next) }
            }
        }

        private suspend fun refreshSimForRecipient() {
            val address = state.value.recipient.trim()
            val remembered = address.takeIf { it.isNotBlank() }?.let { simChoiceStore.rememberedFor(it) }
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
            simUi.value =
                SimUiState(
                    visible = SimSelector.indicatorVisible(activeSims),
                    slot = SimSelector.slotNumberFor(activeSims, chosen) ?: 0,
                    simCount = activeSims.size,
                    operatorName = activeSims.firstOrNull { it.subscriptionId == chosen }?.displayName.orEmpty(),
                )
        }

        fun onBodyChange(value: String) {
            state.value = state.value.copy(body = value)
        }

        /**
         * Dispatches the message. The compose box is consumed OPTIMISTICALLY -
         * body and attachment chips clear the moment Send is tapped, exactly
         * like the conversation compose bar - and, once the outgoing row is
         * persisted (Sending), the screen navigates INTO the new thread via
         * [openThreadFlow]: the Sending->Sent bubble there is the send
         * feedback, so no delayed snackbar is ever the only response to the
         * tap. Double-taps are idempotent twice over: the synchronous
         * SENDING guard and the already-consumed (blank) body both drop the
         * second tap. Only a dispatch failure (nothing persisted, or an MMS
         * row left behind to resend) keeps the user here: the body is
         * RESTORED and [ComposeUiState.sendStatus] reports FAILED so Retry
         * has something to retry. The chosen SIM rides along exactly like a
         * conversation reply.
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
                val messageId =
                    try {
                        when {
                            attachments.isNotEmpty() ->
                                mmsSender
                                    .send(current.recipient.trim(), signedBody(current.body), attachments, chosenSim.value)
                                    .also { failedMmsMessageId = it }
                            retryMmsId != null -> {
                                // Attachments already live on the failed
                                // row; Retry re-dispatches THAT row.
                                mmsSender.resend(retryMmsId)
                                retryMmsId
                            }
                            else -> smsSender.send(current.recipient.trim(), signedBody(current.body), chosenSim.value)
                        }
                    } catch (_: Exception) {
                        // Nothing (new) was persisted: give the text back so
                        // Retry is a real retry, not a blank no-op.
                        state.value = state.value.copy(body = current.body, sendStatus = SendStatus.FAILED)
                        return@launch
                    }
                failedMmsMessageId = null
                // The thread exists with its Sending bubble - go there. The
                // radio's Sent/Failed report resolves on that bubble (and on
                // the row's Retry affordance), not on this screen.
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
                    } catch (_: Exception) {
                        // Nothing was persisted: give the text back so the
                        // user can retry (send now, or long-press again).
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
    }
