package app.clearsms.ui.components

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class SelectionStateTest {
    @Test
    fun `long-press enters selection mode with the pressed item`() {
        val state = SelectionState<Long>().enter(7L)
        assertThat(state.active).isTrue()
        assertThat(state.selected).containsExactly(7L)
        assertThat(state.count).isEqualTo(1)
    }

    @Test
    fun `toggle adds and removes items`() {
        val state = SelectionState<Long>().enter(1L).toggle(2L)
        assertThat(state.selected).containsExactly(1L, 2L)
        assertThat(state.toggle(2L).selected).containsExactly(1L)
    }

    @Test
    fun `deselecting the last item exits selection mode`() {
        val state = SelectionState<Long>().enter(1L).toggle(1L)
        assertThat(state.active).isFalse()
        assertThat(state.selected).isEmpty()
    }

    @Test
    fun `select all merges with the existing selection`() {
        val state = SelectionState<Long>().enter(1L).withAll(listOf(1L, 2L, 3L))
        assertThat(state.selected).containsExactly(1L, 2L, 3L)
        assertThat(state.active).isTrue()
    }

    @Test
    fun `clear exits selection mode and discards everything`() {
        val state = SelectionState<Long>().enter(1L).withAll(listOf(2L, 3L)).clear()
        assertThat(state.active).isFalse()
        assertThat(state.selected).isEmpty()
    }

    @Test
    fun `toggle and select all are no-ops while inactive`() {
        val inactive = SelectionState<Long>()
        assertThat(inactive.toggle(5L)).isEqualTo(inactive)
        assertThat(inactive.withAll(listOf(1L, 2L))).isEqualTo(inactive)
    }

    @Test
    fun `isSelected reflects membership`() {
        val state = SelectionState<Long>().enter(9L)
        assertThat(state.isSelected(9L)).isTrue()
        assertThat(state.isSelected(8L)).isFalse()
    }
}
