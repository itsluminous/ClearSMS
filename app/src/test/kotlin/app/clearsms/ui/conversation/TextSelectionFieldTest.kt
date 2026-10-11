package app.clearsms.ui.conversation

import androidx.compose.ui.text.TextRange
import app.clearsms.R
import app.clearsms.ui.conversation.TextSelectionField.CopyAction
import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Entering text-selection mode (issue #103) must yield a selection that
 * already spans the whole message: that is what puts both handles on screen
 * with no further gesture, and what keeps "copy the entire message" one tap
 * away from this mode. And the bar's Copy must then follow the handles: the
 * narrowed range once one is dragged, labelled as such, and nothing at all -
 * never silently the whole message - once a tap has collapsed the selection.
 */
class TextSelectionFieldTest {
    private val body = "Your OTP is 4821. Do not share it."

    @Test
    fun `copy follows the LIVE selection - the whole body on entry, the narrowed range after a drag`() {
        val state = TextSelectionField.state(body)
        assertThat(TextSelectionField.copyAction(state.text, state.selection)).isEqualTo(CopyAction.Whole(body))

        state.edit { selection = TextRange(12, 16) } // a handle drag onto the code
        val narrowed = TextSelectionField.copyAction(state.text, state.selection)
        assertThat(narrowed).isEqualTo(CopyAction.Partial("4821"))
        assertThat(narrowed.text).isNotEqualTo(body)
    }

    @Test
    fun `a range touching one end but not the other is still partial`() {
        assertThat(TextSelectionField.copyAction(body, TextRange(0, 4))).isEqualTo(CopyAction.Partial("Your"))
        assertThat(TextSelectionField.copyAction(body, TextRange(18, body.length))).isEqualTo(CopyAction.Partial("Do not share it."))
    }

    @Test
    fun `handles dragged past each other - a reversed range copies the same text`() {
        assertThat(TextSelectionField.copyAction(body, TextRange(16, 12))).isEqualTo(CopyAction.Partial("4821"))
        assertThat(TextSelectionField.copyAction(body, TextRange(body.length, 0))).isEqualTo(CopyAction.Whole(body))
    }

    @Test
    fun `a collapsed selection offers NOTHING to copy - never the whole message by default`() {
        for (offset in listOf(0, 7, body.length)) {
            val action = TextSelectionField.copyAction(body, TextRange(offset))
            assertThat(action).isEqualTo(CopyAction.Nothing)
            assertThat(action.text).isNull()
        }
    }

    @Test
    fun `an out-of-range selection is clamped instead of throwing`() {
        assertThat(TextSelectionField.copyAction("abc", TextRange(1, 99))).isEqualTo(CopyAction.Partial("bc"))
        assertThat(TextSelectionField.copyAction("abc", TextRange(0, 99))).isEqualTo(CopyAction.Whole("abc"))
        assertThat(TextSelectionField.copyAction("", TextRange(0, 5))).isEqualTo(CopyAction.Nothing)
    }

    @Test
    fun `the label says Copy message only while the whole body is highlighted`() {
        assertThat(TextSelectionField.labelRes(CopyAction.Whole(body))).isEqualTo(R.string.action_copy_message)
        assertThat(TextSelectionField.labelRes(CopyAction.Partial("4821"))).isEqualTo(R.string.action_copy_selected_text)
        // Disabled Copy keeps the "selected text" wording: that is what it
        // would copy, and what the user has to re-select.
        assertThat(TextSelectionField.labelRes(CopyAction.Nothing)).isEqualTo(R.string.action_copy_selected_text)
    }

    @Test
    fun `the initial selection covers every character of the body`() {
        assertThat(TextSelectionField.initialSelection("Your OTP is 4821")).isEqualTo(TextRange(0, 16))
    }

    @Test
    fun `the field state starts with the body text and the whole of it selected`() {
        val body = "Order #1234 shipped.\nTrack: ABC"
        val state = TextSelectionField.state(body)
        assertThat(state.text.toString()).isEqualTo(body)
        assertThat(state.selection).isEqualTo(TextRange(0, body.length))
        assertThat(state.selection.collapsed).isFalse()
        assertThat(state.selection.length).isEqualTo(body.length)
    }

    @Test
    fun `a one-character body still gets a real range, so the handles have something to anchor`() {
        assertThat(TextSelectionField.state("K").selection).isEqualTo(TextRange(0, 1))
    }

    @Test
    fun `an empty body collapses at zero - the bubble never offers text mode for it anyway`() {
        assertThat(TextSelectionField.state("").selection).isEqualTo(TextRange.Zero)
        assertThat(ConversationSelectionMode.hasSelectableText("", null)).isFalse()
    }
}
