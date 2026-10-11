package app.clearsms.ui.conversation

import androidx.compose.ui.text.TextRange
import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Entering text-selection mode (issue #103) must yield a selection that
 * already spans the whole message: that is what puts both handles on screen
 * with no further gesture, and what keeps "copy the entire message" one tap
 * away from this mode.
 */
class TextSelectionFieldTest {
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
