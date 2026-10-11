package app.clearsms.ui.conversation

import app.clearsms.data.db.MmsStatus
import app.clearsms.ui.components.SelectionState
import app.clearsms.ui.conversation.ConversationSelectionMode.LongPress
import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * The state machine behind partial text selection (issue #103): normal ->
 * message selected -> text selection -> back, and the rule that the text
 * affordance is offered ONLY for an already selected message with body text.
 */
class ConversationSelectionModeTest {
    private val fresh = ConversationSelectionMode()

    @Test
    fun `first long-press enters message selection exactly as before`() {
        assertThat(fresh.longPressAction(1L, selectableText = true)).isEqualTo(LongPress.ENTER_SELECTION)
        val afterFirst = fresh.longPress(1L, selectableText = true)
        assertThat(afterFirst.selection).isEqualTo(SelectionState<Long>().enter(1L))
        assertThat(afterFirst.textSelecting).isFalse()
    }

    @Test
    fun `long-press on an ALREADY selected message with text offers text selection`() {
        val selected = fresh.longPress(1L, selectableText = true)
        assertThat(selected.longPressAction(1L, selectableText = true)).isEqualTo(LongPress.SELECT_TEXT)
        val textMode = selected.longPress(1L, selectableText = true)
        assertThat(textMode.textSelecting).isTrue()
        assertThat(textMode.isTextSelecting(1L)).isTrue()
        assertThat(textMode.isTextSelecting(2L)).isFalse()
        // The message selection is untouched underneath.
        assertThat(textMode.selection.isSelected(1L)).isTrue()
        assertThat(textMode.selection.active).isTrue()
    }

    @Test
    fun `long-press on an UNselected message during selection toggles it - today's behaviour, no text mode`() {
        val selected = fresh.longPress(1L, selectableText = true)
        assertThat(selected.longPressAction(2L, selectableText = true)).isEqualTo(LongPress.TOGGLE_SELECTION)
        val two = selected.longPress(2L, selectableText = true)
        assertThat(two.selection.selected).containsExactly(1L, 2L)
        assertThat(two.textSelecting).isFalse()
    }

    @Test
    fun `a selected message WITHOUT text never offers text selection - image-only MMS keeps the toggle meaning`() {
        val selected = fresh.longPress(1L, selectableText = false)
        assertThat(selected.longPressAction(1L, selectableText = false)).isEqualTo(LongPress.TOGGLE_SELECTION)
        // Toggling the only selected message leaves selection mode, as before.
        val after = selected.longPress(1L, selectableText = false)
        assertThat(after.selection.active).isFalse()
        assertThat(after.textSelecting).isFalse()
    }

    @Test
    fun `exit path - leaving text mode keeps the message selection, leaving selection drops both`() {
        val textMode = fresh.longPress(1L, true).longPress(1L, true)
        val back = textMode.exitTextSelection()
        assertThat(back.textSelecting).isFalse()
        assertThat(back.selection.isSelected(1L)).isTrue()
        assertThat(back.selection.active).isTrue()
        val gone = textMode.exit()
        assertThat(gone).isEqualTo(fresh)
    }

    @Test
    fun `touching another bubble leaves text mode and then toggles as selection mode would`() {
        val textMode = fresh.longPress(1L, true).longPress(1L, true)
        val tapped = textMode.tap(2L)
        assertThat(tapped.textSelecting).isFalse()
        assertThat(tapped.selection.selected).containsExactly(1L, 2L)
        val longPressed = textMode.longPress(3L, true)
        assertThat(longPressed.textSelecting).isFalse()
        assertThat(longPressed.selection.selected).containsExactly(1L, 3L)
    }

    @Test
    fun `invariant - text mode cannot outlive the selection of its message`() {
        val textMode = fresh.longPress(1L, true).longPress(1L, true)
        // Deselecting the text-mode message (sole selection) leaves everything.
        assertThat(textMode.tap(1L)).isEqualTo(fresh)
        // Deselecting it while others remain keeps selection, drops text mode.
        val two = textMode.tap(2L).longPress(1L, true)
        assertThat(two.textSelecting).isTrue()
        val dropped = two.tap(1L)
        assertThat(dropped.textSelecting).isFalse()
        assertThat(dropped.selection.selected).containsExactly(2L)
    }

    @Test
    fun `select all keeps text mode - its message is still selected`() {
        val textMode = fresh.longPress(1L, true).longPress(1L, true)
        val all = textMode.withAll(listOf(1L, 2L, 3L))
        assertThat(all.textSelecting).isTrue()
        assertThat(all.selection.selected).containsExactly(1L, 2L, 3L)
    }

    @Test
    fun `selectable text mirrors what the bubble renders - body present and MMS not pending or failed`() {
        assertThat(ConversationSelectionMode.hasSelectableText("Hello", null)).isTrue()
        assertThat(ConversationSelectionMode.hasSelectableText("Hello", MmsStatus.DOWNLOADED)).isTrue()
        assertThat(ConversationSelectionMode.hasSelectableText("", null)).isFalse()
        assertThat(ConversationSelectionMode.hasSelectableText("   ", MmsStatus.DOWNLOADED)).isFalse()
        // Pending / failed MMS replace the body with a status line: nothing to select.
        assertThat(ConversationSelectionMode.hasSelectableText("Hello", MmsStatus.PENDING)).isFalse()
        assertThat(ConversationSelectionMode.hasSelectableText("Hello", MmsStatus.FAILED)).isFalse()
    }
}
