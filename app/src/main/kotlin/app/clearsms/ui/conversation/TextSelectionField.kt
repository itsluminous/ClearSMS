package app.clearsms.ui.conversation

import androidx.annotation.StringRes
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.ui.text.TextRange
import app.clearsms.R

/**
 * The state behind a bubble in text-selection mode (issue #103).
 *
 * The body is rendered as a READ-ONLY text field whose selection already
 * spans the whole message, because that is the only way to have Compose draw
 * the selection handles the moment the mode starts: `SelectionContainer`
 * creates a selection only from a long-press within the text and offers no
 * way to seed one, so it cost the user a third long-press before anything
 * appeared. A `TextFieldState` with a non-collapsed selection shows both
 * handles as soon as the field has focus.
 *
 * Pre-selecting the WHOLE message is the intended behaviour, not a
 * shortcut: the handles need a range to anchor to, and "copy the entire
 * message" stays one tap away from this mode - the user drags a handle
 * inward only when they want less (what #103 praised in YAATA).
 *
 * The selection bar's Copy is the reliable way OUT of this mode. Compose's
 * own floating toolbar starts in its `None` state when the field is focused
 * programmatically, and a handle drag only hides and restores whatever state
 * was there - so on a fresh entry no toolbar ever appears, and a user who
 * narrowed the range had no honest Copy at all (the bar's copied the whole
 * message, silently). The screen therefore owns the field state and the
 * bar's Copy reads the LIVE selection through [copyAction]: correct the
 * moment the mode opens (whole message) and after any drag (the narrowed
 * range), with a label that says which ([labelRes]).
 */
internal object TextSelectionField {
    /** Every character of [body]: start to end, never collapsed for a non-empty body. */
    fun initialSelection(body: String): TextRange = TextRange(0, body.length)

    /** A fresh field state holding [body] with [initialSelection] applied. */
    fun state(body: String): TextFieldState = TextFieldState(body, initialSelection(body))

    /**
     * What the bar's Copy does in text mode, derived from the field's LIVE
     * selection - never from the message body on its own.
     */
    sealed interface CopyAction {
        /** Text to put on the clipboard; null when there is nothing highlighted. */
        val text: String?

        /** The handles still enclose the whole message: Copy means "Copy message", as before. */
        data class Whole(
            override val text: String,
        ) : CopyAction

        /** A handle was dragged inward: Copy copies exactly the highlighted range. */
        data class Partial(
            override val text: String,
        ) : CopyAction

        /**
         * A tap collapsed the selection: nothing is highlighted, so Copy is
         * DISABLED rather than quietly falling back to the whole message -
         * that would be the same silent wrong result the mode exists to
         * prevent. The user re-selects with a long-press in the text (a word,
         * with handles) or steps back with Close / Back, where whole-message
         * Copy is one tap away.
         */
        data object Nothing : CopyAction {
            override val text: String? get() = null
        }
    }

    /** Classifies [selection] over [text] - see [CopyAction]. Tolerates a reversed range (handles crossed). */
    fun copyAction(
        text: CharSequence,
        selection: TextRange,
    ): CopyAction {
        val start = selection.min.coerceIn(0, text.length)
        val end = selection.max.coerceIn(0, text.length)
        return when {
            start == end -> CopyAction.Nothing
            start == 0 && end == text.length -> CopyAction.Whole(text.toString())
            else -> CopyAction.Partial(text.substring(start, end))
        }
    }

    /** The bar's Copy label for [action]: it must never claim "Copy message" while only part of one is highlighted. */
    @StringRes
    fun labelRes(action: CopyAction): Int =
        when (action) {
            is CopyAction.Whole -> R.string.action_copy_message
            is CopyAction.Partial, CopyAction.Nothing -> R.string.action_copy_selected_text
        }
}
