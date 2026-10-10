package app.clearsms.ui.conversation

import app.clearsms.data.db.MmsStatus
import app.clearsms.ui.components.SelectionState

/**
 * The conversation's selection state machine: the ordinary message
 * multi-select ([SelectionState]) plus, NESTED inside it, partial TEXT
 * selection within one selected message (issue #103).
 *
 * The interaction is Telegram's, chosen by the maintainer because it adds no
 * new gesture: the FIRST long-press on a message enters message selection
 * exactly as before; a long-press on a message that is ALREADY SELECTED
 * offers text selection inside that one bubble. The two meanings of a
 * long-press are told apart purely by [SelectionState.isSelected], so
 * nothing about the first long-press, the tap-to-toggle or the selection
 * bar changes.
 *
 * Invariant: [textSelectionId] is non-null only while selection mode is
 * active AND that message is selected. Every transition re-establishes it
 * ([normalized]), so deselecting, deleting or exiting can never leave a
 * bubble stranded in text mode.
 *
 * Exit path: [exitTextSelection] (the bar's Close, or Back) returns to
 * message selection WITH the selection intact - the user built it
 * deliberately, and the whole-message Copy must stay one tap away. A second
 * Back then leaves selection mode as it always has. Any tap or long-press on
 * ANOTHER bubble also leaves text mode first and then does what selection
 * mode would have done (toggle), so bubbles never feel dead while one is in
 * text mode. The bubble in text mode itself has no tap/long-press of its
 * own: Compose's text-selection gestures own it (see ConversationScreen).
 */
data class ConversationSelectionMode(
    val selection: SelectionState<Long> = SelectionState(),
    val textSelectionId: Long? = null,
) {
    /** What a bubble long-press means right now (see [longPress]). */
    enum class LongPress {
        /** No selection yet: enters selection mode with this message - today's behaviour. */
        ENTER_SELECTION,

        /** Selection active, message not selected (or has no text): toggles it - today's behaviour. */
        TOGGLE_SELECTION,

        /** Selection active and this message is ALREADY selected with text: offer text selection. */
        SELECT_TEXT,
    }

    /** True while one message is in text-selection mode. */
    val textSelecting: Boolean get() = textSelectionId != null

    fun isTextSelecting(messageId: Long): Boolean = textSelectionId == messageId

    /**
     * Routes a long-press. [selectableText] is [hasSelectableText] for the
     * message: an image-only MMS, or one whose download is pending or failed,
     * has no text to select, so its second long-press keeps the old meaning
     * (toggle) instead of offering a dead text mode.
     */
    fun longPressAction(
        messageId: Long,
        selectableText: Boolean,
    ): LongPress =
        when {
            !selection.active -> LongPress.ENTER_SELECTION
            selection.isSelected(messageId) && selectableText -> LongPress.SELECT_TEXT
            else -> LongPress.TOGGLE_SELECTION
        }

    /** Applies [longPressAction]. */
    fun longPress(
        messageId: Long,
        selectableText: Boolean,
    ): ConversationSelectionMode =
        when (longPressAction(messageId, selectableText)) {
            LongPress.ENTER_SELECTION -> ConversationSelectionMode(selection.enter(messageId))
            LongPress.SELECT_TEXT -> copy(textSelectionId = messageId).normalized()
            LongPress.TOGGLE_SELECTION -> tap(messageId)
        }

    /** A tap in selection mode: toggles the message and leaves text mode (another bubble was touched). */
    fun tap(messageId: Long): ConversationSelectionMode = ConversationSelectionMode(selection.toggle(messageId)).normalized()

    /** Back to plain message selection; the selection itself is kept. */
    fun exitTextSelection(): ConversationSelectionMode = copy(textSelectionId = null)

    /** Leaves selection mode entirely (and therefore text mode). */
    fun exit(): ConversationSelectionMode = ConversationSelectionMode()

    /** Select all: text mode survives, its message is still selected. */
    fun withAll(ids: Collection<Long>): ConversationSelectionMode = copy(selection = selection.withAll(ids)).normalized()

    private fun normalized(): ConversationSelectionMode =
        if (textSelectionId != null && !(selection.active && selection.isSelected(textSelectionId))) {
            copy(textSelectionId = null)
        } else {
            this
        }

    companion object {
        /**
         * Whether the bubble shows body text that can be selected. The
         * bubble renders the body only when it is not blank and the MMS is
         * not still downloading / failed (those states replace the body with
         * a status line), so this mirrors exactly what is on screen.
         */
        fun hasSelectableText(
            body: String,
            mmsStatus: MmsStatus?,
        ): Boolean = body.isNotBlank() && mmsStatus != MmsStatus.PENDING && mmsStatus != MmsStatus.FAILED
    }
}
