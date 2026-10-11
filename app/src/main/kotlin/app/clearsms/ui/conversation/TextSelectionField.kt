package app.clearsms.ui.conversation

import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.ui.text.TextRange

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
 */
internal object TextSelectionField {
    /** Every character of [body]: start to end, never collapsed for a non-empty body. */
    fun initialSelection(body: String): TextRange = TextRange(0, body.length)

    /** A fresh field state holding [body] with [initialSelection] applied. */
    fun state(body: String): TextFieldState = TextFieldState(body, initialSelection(body))
}
