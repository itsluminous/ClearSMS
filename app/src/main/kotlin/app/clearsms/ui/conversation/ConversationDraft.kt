package app.clearsms.ui.conversation

import app.clearsms.data.repository.MessageRepository
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * The compose field's per-thread draft: loads the persisted draft on open,
 * write-through persists every edit (so the draft survives process death,
 * not just navigation), and [consume] clears it the moment the text is
 * handed to a send or a schedule - neither may leave a leftover draft.
 *
 * [seed] is text INHERITED from the new-message composer that routed the
 * user here (issue #119). It is shown immediately, after any saved draft
 * (the saved text is older), but NOT persisted as this thread's draft
 * until the user edits or sends it: the same text still sits in the
 * composer beneath for Back, and a mis-tapped contact must not end up
 * with a stale "Draft" preview from text that was never meant for them.
 *
 * Persistence uses `collectLatest`, so a burst of keystrokes collapses to
 * the newest value and a stale write can never overtake a newer one.
 */
class ConversationDraft(
    private val threadId: Long,
    private val repository: MessageRepository,
    scope: CoroutineScope,
    ioDispatcher: CoroutineDispatcher,
    seed: String? = null,
) {
    private val draftText = MutableStateFlow("")

    /** Current compose text, restored from the saved draft on open. */
    val text: StateFlow<String> = draftText.asStateFlow()

    /** The one value the collector must not write: the untouched inherited seed. */
    @Volatile
    private var transient: String? = null

    init {
        scope.launch(ioDispatcher) {
            val saved = repository.draftFor(threadId).orEmpty()
            // compareAndSet: text typed before the load lands is never
            // clobbered by the (older) saved draft.
            if (saved.isNotEmpty()) draftText.compareAndSet("", saved)
            if (!seed.isNullOrBlank()) {
                // The inherited text is the NEWEST: it follows whatever is
                // already here (a saved draft, or text typed meanwhile).
                draftText.update { current -> if (current.isBlank()) seed else "$current\n$seed" }
                transient = draftText.value
            }
            // The first collected emission re-saves the loaded value - a
            // deliberate no-op that keeps the collector logic trivial. An
            // untouched seed is the one exception: it stays in memory only.
            draftText.collectLatest { if (it != transient) repository.saveDraft(threadId, it) }
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

    /**
     * A delayed send was cancelled: put [body] back so the user can edit
     * and re-send - a cancel that discarded the text would be data loss.
     * Text typed DURING the delay is kept too: the restored body is
     * prepended (it is the older of the two), never clobbered over it.
     */
    fun restore(body: String) {
        draftText.value =
            draftText.value.let { current ->
                if (current.isBlank()) body else "$body\n$current"
            }
    }
}
