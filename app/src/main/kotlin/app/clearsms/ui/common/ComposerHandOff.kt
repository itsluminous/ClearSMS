package app.clearsms.ui.common

import app.clearsms.mms.StagedAttachment
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * What the new-message composer hands to the conversation it routes the
 * user into (issue #119): the text typed so far, the chips staged so far
 * (copied on arrival - see [ComposerAttachments.adoptCopies]), content
 * shared into the composer that is not staged yet, and - after a Send
 * that delayed sending turned into a pending schedule - the delayed row
 * whose Cancel bar the conversation must show.
 *
 * Nothing here is a draft the user saved: the conversation shows it in
 * its compose bar without persisting it until the user edits it there,
 * so a mis-tapped contact's thread never gains a stale "Draft" from text
 * that still sits, intact, in the composer beneath.
 */
data class ComposerHandOff(
    val draft: String = "",
    val attachments: List<StagedAttachment> = emptyList(),
    /**
     * Whether [attachments] are the receiver's to keep (the composer was
     * REPLACED and consumed them) or must be copied (the composer stays
     * beneath for Back and keeps its own files).
     */
    val attachmentsOwned: Boolean = false,
    val delayedMessageId: Long? = null,
    val stashedAtMs: Long = System.currentTimeMillis(),
) {
    val isEmpty: Boolean
        get() = draft.isBlank() && attachments.isEmpty() && delayedMessageId == null
}

/**
 * In-process mailbox for [ComposerHandOff]s, keyed by the thread being
 * opened and consumed exactly once by that conversation's ViewModel on
 * creation. In memory on purpose: the hand-off is navigation-local (the
 * conversation is created in the same instant the composer navigates), and
 * staged attachments do not survive process death anyway. A hand-off
 * nobody collected within [TTL_MS] (a navigation that never landed) is
 * dropped rather than surfacing later as text from nowhere.
 */
@Singleton
class ComposerHandOffs
    @Inject
    constructor() {
        private val pending = ConcurrentHashMap<Long, ComposerHandOff>()

        fun stash(
            threadId: Long,
            handOff: ComposerHandOff,
        ) {
            if (handOff.isEmpty) return
            pending[threadId] = handOff
        }

        fun take(
            threadId: Long,
            nowMs: Long = System.currentTimeMillis(),
        ): ComposerHandOff? = pending.remove(threadId)?.takeIf { nowMs - it.stashedAtMs <= TTL_MS }

        companion object {
            const val TTL_MS = 60_000L
        }
    }
