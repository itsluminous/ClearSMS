package app.clearsms.ui.composemsg

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.clearsms.data.prefs.SettingsRepository
import app.clearsms.di.IoDispatcher
import app.clearsms.sms.SimChoiceStore
import app.clearsms.sms.SimInfo
import app.clearsms.sms.SimSelector
import app.clearsms.sms.SmsSender
import app.clearsms.sms.SubscriptionSource
import app.clearsms.ui.components.SimUiState
import app.clearsms.ui.conversation.SendStatus
import app.clearsms.ui.conversation.SentMessageWatcher
import app.clearsms.work.MessageScheduler
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
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
    /** True once a schedule was created: the thread exists, leave the screen. */
    val scheduled: Boolean = false,
)

@OptIn(FlowPreview::class)
@HiltViewModel
class ComposeMessageViewModel
    @Inject
    constructor(
        savedStateHandle: SavedStateHandle,
        private val smsSender: SmsSender,
        private val sentMessageWatcher: SentMessageWatcher,
        private val settings: SettingsRepository,
        private val contactSuggestions: ContactSuggestions,
        private val subscriptionSource: SubscriptionSource,
        private val simChoiceStore: SimChoiceStore,
        private val messageScheduler: MessageScheduler,
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

        private val recipientQuery = MutableStateFlow("")

        /** Active SIMs, primed once in init; empty on single-SIM devices. */
        @Volatile
        private var activeSims: List<SimInfo> = emptyList()

        /** Subscription the next send will use; null = system default manager. */
        private val chosenSim = MutableStateFlow<Int?>(null)

        private val simUi = MutableStateFlow(SimUiState())
        val simState: StateFlow<SimUiState> = simUi.asStateFlow()

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
        }

        val suggestions: StateFlow<List<ContactSuggestion>> =
            recipientQuery
                .debounce(200)
                .map { query -> contactSuggestions.search(query) }
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
         * Dispatches the message and resolves [ComposeUiState.sendStatus]
         * from the persisted message status: [SmsSender] writes the outgoing
         * row at Sending, and [SentMessageWatcher] resolves it to Sent or
         * Failed from the recorded radio reports. Retrying is calling [send]
         * again. The chosen SIM rides along exactly like a conversation
         * reply.
         */
        fun send() {
            val current = state.value
            if (current.recipient.isBlank() || current.body.isBlank()) return
            if (current.sendStatus == SendStatus.SENDING) return
            state.value = current.copy(sendStatus = SendStatus.SENDING)
            viewModelScope.launch(ioDispatcher) {
                val status =
                    try {
                        val messageId =
                            smsSender.send(current.recipient.trim(), signedBody(current.body), chosenSim.value)
                        sentMessageWatcher.await(messageId)
                    } catch (_: Exception) {
                        SendStatus.FAILED
                    }
                state.value = state.value.copy(sendStatus = status)
            }
        }

        /**
         * Schedules the message instead of sending it, through the SAME
         * [MessageScheduler] the conversation screen uses: the thread is
         * created with a durable SCHEDULED row and an armed alarm - no
         * forked send path.
         */
        fun schedule(scheduledAtMs: Long) {
            val current = state.value
            if (current.recipient.isBlank() || current.body.isBlank()) return
            if (current.sendStatus == SendStatus.SENDING) return
            viewModelScope.launch(ioDispatcher) {
                messageScheduler.schedule(
                    destination = current.recipient.trim(),
                    body = signedBody(current.body),
                    subscriptionId = chosenSim.value,
                    scheduledAtMs = scheduledAtMs,
                )
                state.value = state.value.copy(scheduled = true)
            }
        }

        /** The body with the configured signature appended, like every send. */
        private suspend fun signedBody(body: String): String {
            val signature = settings.signature.first()
            return if (signature.isNotBlank()) "$body\n$signature" else body
        }
    }
