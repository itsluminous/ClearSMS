package app.clearsms.ui.settings

import app.clearsms.ui.navigation.PillConfig
import app.clearsms.ui.navigation.orderedPills
import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * The pill-reorder arithmetic behind [PillOrderDialog], as pure logic (no
 * Compose harness in this repo): [movedPill] for the move itself, and
 * [PillDragState] for how a drag - or a screen reader's Move up / Move down
 * action - turns into exactly one persisted order.
 */
class PillReorderTest {
    private val abcde = listOf("a", "b", "c", "d", "e")

    // ---- movedPill -----------------------------------------------------

    @Test
    fun `first to last`() {
        assertThat(movedPill(abcde, 0, 4)).containsExactly("b", "c", "d", "e", "a").inOrder()
    }

    @Test
    fun `last to first`() {
        assertThat(movedPill(abcde, 4, 0)).containsExactly("e", "a", "b", "c", "d").inOrder()
    }

    @Test
    fun `adjacent swaps in both directions`() {
        assertThat(movedPill(abcde, 1, 2)).containsExactly("a", "c", "b", "d", "e").inOrder()
        assertThat(movedPill(abcde, 2, 1)).containsExactly("a", "c", "b", "d", "e").inOrder()
    }

    @Test
    fun `a move onto its own slot is a no-op that returns the same list`() {
        assertThat(movedPill(abcde, 2, 2)).isSameInstanceAs(abcde)
    }

    @Test
    fun `out-of-range indices clamp to the ends instead of throwing`() {
        // Dragged far past the top: lands first.
        assertThat(movedPill(abcde, 3, -100)).containsExactly("d", "a", "b", "c", "e").inOrder()
        // Dragged far past the bottom: lands last.
        assertThat(movedPill(abcde, 1, 100)).containsExactly("a", "c", "d", "e", "b").inOrder()
        // A stale source index clamps too.
        assertThat(movedPill(abcde, 99, 0)).containsExactly("e", "a", "b", "c", "d").inOrder()
        assertThat(movedPill(abcde, -5, 4)).containsExactly("b", "c", "d", "e", "a").inOrder()
    }

    @Test
    fun `single-item and empty lists are returned untouched`() {
        val one = listOf("only")
        assertThat(movedPill(one, 0, 5)).isSameInstanceAs(one)
        assertThat(movedPill(one, -1, 0)).isSameInstanceAs(one)
        val none = emptyList<String>()
        assertThat(movedPill(none, 0, 0)).isSameInstanceAs(none)
    }

    @Test
    fun `a move never loses or duplicates a pill`() {
        for (from in -1..5) {
            for (to in -1..5) {
                assertThat(movedPill(abcde, from, to)).containsExactlyElementsIn(abcde)
            }
        }
        // The input list is never mutated.
        assertThat(abcde).containsExactly("a", "b", "c", "d", "e").inOrder()
    }

    // ---- PillDragState: the gesture as a state machine -------------------

    private val rowPx = 100f

    /** Simulates the gesture shell: begin, a stream of finger moves, release. */
    private fun drag(
        start: PillDragState<String>,
        from: Int,
        vararg deltas: Float,
    ): PillDragState.Settled<String> {
        var state = start.begin(from)
        deltas.forEach { state = state.dragBy(it, rowPx) }
        return state.finish()
    }

    @Test
    fun `the dragged row follows the finger and swaps once it passes half a row`() {
        var state = PillDragState(abcde).begin(1)
        state = state.dragBy(40f, rowPx)
        assertThat(state.order).isEqualTo(abcde) // not yet half a row
        assertThat(state.offsetPx).isEqualTo(40f)
        assertThat(state.active).isEqualTo(1)

        state = state.dragBy(20f, rowPx) // 60 px: past the midpoint
        assertThat(state.order).containsExactly("a", "c", "b", "d", "e").inOrder()
        assertThat(state.active).isEqualTo(2)
        // Re-based on the new slot, so the row keeps following the finger.
        assertThat(state.offsetPx).isEqualTo(-40f)
    }

    @Test
    fun `persistence happens once per completed drag, never while the finger moves`() {
        val commits = mutableListOf<List<String>>()
        var state = PillDragState(abcde).begin(0)
        // Many pixel-level events: none of them yields anything to persist -
        // dragBy returns only a state, so the shell has nothing to commit.
        repeat(35) { state = state.dragBy(10f, rowPx) }
        assertThat(state.order).containsExactly("b", "c", "d", "e", "a").inOrder()
        assertThat(commits).isEmpty()

        val settled = state.finish()
        settled.committed?.let(commits::add)
        assertThat(commits).containsExactly(listOf("b", "c", "d", "e", "a"))
        assertThat(settled.state.isDragging).isFalse()
        assertThat(settled.state.offsetPx).isEqualTo(0f)

        // Finishing again with no drag in flight persists nothing.
        settled.state
            .finish()
            .committed
            ?.let(commits::add)
        assertThat(commits).hasSize(1)
    }

    @Test
    fun `a drag that ends where it started persists nothing`() {
        val wobble = drag(PillDragState(abcde), 2, 30f, -30f, 20f, -20f)
        assertThat(wobble.committed).isNull()
        assertThat(wobble.state.order).isEqualTo(abcde)
        // Out and back again: two swaps that cancel out are still no change.
        val outAndBack = drag(PillDragState(abcde), 2, 70f, -70f)
        assertThat(outAndBack.committed).isNull()
        assertThat(outAndBack.state.order).isEqualTo(abcde)
    }

    @Test
    fun `dragging past the first or last row clamps and the row stays in the list`() {
        val up = PillDragState(abcde).begin(0).dragBy(-5000f, rowPx)
        assertThat(up.order).isEqualTo(abcde)
        assertThat(up.active).isEqualTo(0)
        assertThat(up.offsetPx).isEqualTo(0f) // cannot be pulled above the list

        val down = PillDragState(abcde).begin(4).dragBy(5000f, rowPx)
        assertThat(down.order).isEqualTo(abcde)
        assertThat(down.active).isEqualTo(4)
        assertThat(down.offsetPx).isEqualTo(0f)

        val overshoot = drag(PillDragState(abcde), 1, 5000f)
        assertThat(overshoot.committed).containsExactly("a", "c", "d", "e", "b").inOrder()
    }

    @Test
    fun `a release mid-row settles on the nearest slot, deterministically`() {
        // 49 px: closer to the original slot.
        assertThat(drag(PillDragState(abcde), 1, 49f).committed).isNull()
        // 51 px: closer to the next slot.
        assertThat(drag(PillDragState(abcde), 1, 51f).committed).containsExactly("a", "c", "b", "d", "e").inOrder()
        // 249 px: two and a half rows, rounds to two.
        assertThat(drag(PillDragState(abcde), 0, 249f).committed).containsExactly("b", "c", "a", "d", "e").inOrder()
        // The same input always gives the same answer.
        assertThat(drag(PillDragState(abcde), 1, 51f)).isEqualTo(drag(PillDragState(abcde), 1, 51f))
    }

    @Test
    fun `a fast flick spanning several rows in one event lands on the right slot`() {
        val settled = drag(PillDragState(abcde), 0, 320f)
        assertThat(settled.committed).containsExactly("b", "c", "d", "a", "e").inOrder()
    }

    @Test
    fun `before the row is measured the offset accumulates but nothing swaps`() {
        val state = PillDragState(abcde).begin(1).dragBy(500f, 0f)
        assertThat(state.order).isEqualTo(abcde)
        assertThat(state.offsetPx).isEqualTo(500f)
        // Once measured, the accumulated distance is honoured.
        val measured = state.dragBy(0f, rowPx)
        assertThat(measured.active).isEqualTo(4)
        assertThat(measured.order).containsExactly("a", "c", "d", "e", "b").inOrder()
    }

    @Test
    fun `cancel restores the order the drag started from`() {
        val state = PillDragState(abcde).begin(0).dragBy(250f, rowPx)
        assertThat(state.order).isNotEqualTo(abcde)
        val cancelled = state.cancel()
        assertThat(cancelled.order).isEqualTo(abcde)
        assertThat(cancelled.isDragging).isFalse()
        assertThat(cancelled.finish().committed).isNull()
    }

    @Test
    fun `begin with a bad index or a single row is harmless`() {
        assertThat(PillDragState(abcde).begin(9).isDragging).isFalse()
        assertThat(PillDragState(abcde).begin(-1).isDragging).isFalse()
        val single = PillDragState(listOf("only"))
        val settled = drag(single, 0, 300f, -900f)
        assertThat(settled.committed).isNull()
        assertThat(settled.state.order).containsExactly("only")
        // Dragging with nothing active is a no-op too.
        assertThat(PillDragState(abcde).dragBy(100f, rowPx)).isEqualTo(PillDragState(abcde))
    }

    // ---- the accessible path ----------------------------------------------

    @Test
    fun `move up and move down commit immediately, once each, and clamp at the ends`() {
        val commits = mutableListOf<List<String>>()

        fun apply(settled: PillDragState.Settled<String>): PillDragState<String> {
            settled.committed?.let(commits::add)
            return settled.state
        }
        var state = apply(PillDragState(abcde).moveBy(1, -1))
        assertThat(state.order).containsExactly("b", "a", "c", "d", "e").inOrder()
        state = apply(state.moveBy(0, +1))
        assertThat(state.order).isEqualTo(abcde)
        assertThat(commits).hasSize(2)

        // At the ends the action is a no-op that persists nothing.
        state = apply(state.moveBy(0, -1))
        state = apply(state.moveBy(4, +1))
        assertThat(state.order).isEqualTo(abcde)
        assertThat(commits).hasSize(2)
    }

    @Test
    fun `an accessibility action during a drag abandons the drag first`() {
        val mid = PillDragState(abcde).begin(0).dragBy(250f, rowPx)
        val settled = mid.moveBy(4, -1)
        // The half-finished drag is discarded; the action applies to the
        // order the drag started from.
        assertThat(settled.committed).containsExactly("a", "b", "c", "e", "d").inOrder()
        assertThat(settled.state.isDragging).isFalse()
    }

    // ---- reset and hiding -------------------------------------------------

    @Test
    fun `reset to default is the declaration order and drops nothing`() {
        // What the Reset button does: the ViewModel stores an empty order and
        // the lenient reader (orderedPills) yields the declaration order.
        val declaration = abcde
        val custom = movedPill(movedPill(declaration, 0, 4), 2, 0)
        assertThat(custom).isNotEqualTo(declaration)
        val reset = orderedPills(emptyList(), declaration)
        assertThat(reset).isEqualTo(declaration)
        // A fresh dialog state after reset lists every pill, unmoved.
        assertThat(PillDragState(reset).order).isEqualTo(declaration)
    }

    @Test
    fun `hiding a pill does not disturb the order, and a hidden pill still has a position`() {
        val order = movedPill(abcde, 4, 0) // e a b c d
        val config = PillConfig(all = abcde, order = order, hidden = setOf("a", "e"))
        // The dialog lists every pill, hidden ones in their slot.
        assertThat(config.ordered).containsExactly("e", "a", "b", "c", "d").inOrder()
        // Dragging a hidden pill is a normal move: it keeps a position.
        val moved = drag(PillDragState(config.ordered), 0, 450f).committed!!
        assertThat(moved).containsExactly("a", "b", "c", "d", "e").inOrder()
        // Visibility is computed from the order, never the other way round.
        val after = config.copy(order = moved)
        assertThat(after.visible).containsExactly("b", "c", "d").inOrder()
        assertThat(after.hidden).isEqualTo(config.hidden)
        assertThat(after.ordered).isEqualTo(moved)
    }
}
