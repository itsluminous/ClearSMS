package app.clearsms.ui.conversation

import app.clearsms.ui.conversation.MessageHighlightState.Phase
import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * The scroll-to-and-highlight state machine. The paged conversation loads
 * asynchronously, so the machine must keep waiting across page loads until
 * the target message is actually in the loaded window — and must fire the
 * scroll exactly once.
 */
class HighlightStateMachineTest {
    @Test
    fun `no target stays idle forever`() {
        val machine = MessageHighlightState(null)
        assertThat(machine.phase).isEqualTo(Phase.IDLE)
        assertThat(machine.onItemsLoaded(listOf(1L, 2L, 3L))).isNull()
        assertThat(machine.phase).isEqualTo(Phase.IDLE)
    }

    @Test
    fun `nav default of minus one is treated as no target`() {
        val machine = MessageHighlightState(-1L)
        assertThat(machine.phase).isEqualTo(Phase.IDLE)
    }

    @Test
    fun `target in the first load fires the scroll index once`() {
        val machine = MessageHighlightState(22L)
        assertThat(machine.phase).isEqualTo(Phase.PENDING)
        assertThat(machine.onItemsLoaded(listOf(11L, 22L, 33L))).isEqualTo(1)
        assertThat(machine.phase).isEqualTo(Phase.SHOWN)
        // Subsequent loads (scrolling, new pages) never re-fire.
        assertThat(machine.onItemsLoaded(listOf(11L, 22L, 33L))).isNull()
    }

    @Test
    fun `target arriving on a LATER page load still fires`() {
        // Regression: the old implementation consumed the highlight on the
        // first load and silently dropped it if the target was not there yet.
        val machine = MessageHighlightState(99L)
        assertThat(machine.onItemsLoaded(listOf(11L, 22L))).isNull()
        assertThat(machine.phase).isEqualTo(Phase.PENDING)
        assertThat(machine.onItemsLoaded(listOf(11L, 22L, 99L))).isEqualTo(2)
        assertThat(machine.phase).isEqualTo(Phase.SHOWN)
    }

    @Test
    fun `only the target row is highlighted and only while shown`() {
        val machine = MessageHighlightState(22L)
        assertThat(machine.isHighlighted(22L)).isFalse() // still pending
        machine.onItemsLoaded(listOf(22L))
        assertThat(machine.isHighlighted(22L)).isTrue()
        assertThat(machine.isHighlighted(11L)).isFalse()
        machine.onHighlightFinished()
        assertThat(machine.phase).isEqualTo(Phase.DONE)
        assertThat(machine.isHighlighted(22L)).isFalse()
    }

    @Test
    fun `finish before shown does not corrupt the machine`() {
        val machine = MessageHighlightState(22L)
        machine.onHighlightFinished()
        assertThat(machine.phase).isEqualTo(Phase.PENDING)
        assertThat(machine.onItemsLoaded(listOf(22L))).isEqualTo(0)
    }

    @Test
    fun `nav argument mapping - propagated when positive, dropped otherwise`() {
        assertThat(highlightTargetOf(13969L)).isEqualTo(13969L)
        assertThat(highlightTargetOf(-1L)).isNull()
        assertThat(highlightTargetOf(0L)).isNull()
        assertThat(highlightTargetOf(null)).isNull()
    }
}
