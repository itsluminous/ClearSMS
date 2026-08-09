package app.clearsms.ui.conversation

import app.clearsms.data.repository.MessageRepository
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * The compose field's per-thread draft: loads the persisted draft on open,
 * write-through persists every edit (so the draft survives process death,
 * not just navigation), and [consume] clears it the moment the text is
 * handed to a send or a schedule - neither may leave a leftover draft.
 *
 * Persistence uses `collectLatest`, so a burst of keystrokes collapses to
 * the newest value and a stale write can never overtake a newer one.
 */
class ConversationDraft(
    private val threadId: Long,
    private val repository: MessageRepository,
    scope: CoroutineScope,
    ioDispatcher: CoroutineDispatcher,
) {
    private val draftText = MutableStateFlow("")

    /** Current compose text, restored from the saved draft on open. */
    val text: StateFlow<String> = draftText.asStateFlow()

    init {
        scope.launch(ioDispatcher) {
            val saved = repository.draftFor(threadId).orEmpty()
            // compareAndSet: text typed before the load lands is never
            // clobbered by the (older) saved draft.
            if (saved.isNotEmpty()) draftText.compareAndSet("", saved)
            // The first collected emission re-saves the loaded value - a
            // deliberate no-op that keeps the collector logic trivial.
            draftText.collectLatest { repository.saveDraft(threadId, it) }
        }
    }

    /** User edit from the compose field (blank clears the saved draft). */
    fun set(value: String) {
        draftText.value = value
    }

    /**
     * The text was consumed by a send or a schedule: clear the field and the
     * saved draft together.
     */
    fun consume() {
        draftText.value = ""
    }
}
