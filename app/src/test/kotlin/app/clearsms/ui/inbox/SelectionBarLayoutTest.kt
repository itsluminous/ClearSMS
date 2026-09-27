package app.clearsms.ui.inbox

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Layout rules for the inbox selection bar: the pin entry's Pin/Unpin flip
 * and the fixed inline-vs-overflow split that keeps a six-digit
 * "999999 selected" title fully visible (at most three inline icons plus
 * one overflow menu).
 */
class SelectionBarLayoutTest {
    // region pin/unpin mapping

    @Test
    fun `every selected thread pinned means unpin`() {
        assertThat(SelectionBarLayout.isUnpin(selectedCount = 3, pinnedCount = 3)).isTrue()
        assertThat(SelectionBarLayout.pinAction(allSelectedPinned = true)).isEqualTo(SelectionAction.UNPIN)
    }

    @Test
    fun `mixed selection keeps pin`() {
        assertThat(SelectionBarLayout.isUnpin(selectedCount = 3, pinnedCount = 2)).isFalse()
        assertThat(SelectionBarLayout.pinAction(allSelectedPinned = false)).isEqualTo(SelectionAction.PIN)
    }

    @Test
    fun `nothing pinned means pin`() {
        assertThat(SelectionBarLayout.isUnpin(selectedCount = 3, pinnedCount = 0)).isFalse()
    }

    @Test
    fun `empty selection never claims unpin`() {
        assertThat(SelectionBarLayout.isUnpin(selectedCount = 0, pinnedCount = 0)).isFalse()
    }

    // endregion

    // region inline vs overflow split

    @Test
    fun `at most three inline actions, most-used first`() {
        assertThat(SelectionBarLayout.inlineActions)
            .containsExactly(SelectionAction.TOGGLE_READ, SelectionAction.ARCHIVE, SelectionAction.DELETE)
            .inOrder()
    }

    @Test
    fun `overflow for a multi-thread selection holds pin and select-all only`() {
        assertThat(SelectionBarLayout.overflowActions(allSelectedPinned = false, singleThread = false))
            .containsExactly(SelectionAction.PIN, SelectionAction.SELECT_ALL)
            .inOrder()
    }

    @Test
    fun `overflow for a single thread adds mute, block and always-sort-as`() {
        assertThat(SelectionBarLayout.overflowActions(allSelectedPinned = false, singleThread = true))
            .containsExactly(
                SelectionAction.PIN,
                SelectionAction.SELECT_ALL,
                SelectionAction.MUTE,
                SelectionAction.BLOCK,
                SelectionAction.ALWAYS_SORT_AS,
            ).inOrder()
    }

    @Test
    fun `the mute entry is a single toggle - it reads unmute for an already muted thread`() {
        assertThat(SelectionBarLayout.muteAction(singleThreadMuted = false)).isEqualTo(SelectionAction.MUTE)
        assertThat(SelectionBarLayout.muteAction(singleThreadMuted = true)).isEqualTo(SelectionAction.UNMUTE)
        val muted = SelectionBarLayout.overflowActions(allSelectedPinned = false, singleThread = true, singleThreadMuted = true)
        assertThat(muted).contains(SelectionAction.UNMUTE)
        assertThat(muted).doesNotContain(SelectionAction.MUTE)
        // Same slot, same neighbours: only the label flips.
        assertThat(muted.indexOf(SelectionAction.UNMUTE)).isEqualTo(muted.indexOf(SelectionAction.BLOCK) - 1)
    }

    @Test
    fun `mute never appears for a multi-thread selection`() {
        val multi = SelectionBarLayout.overflowActions(allSelectedPinned = false, singleThread = false, singleThreadMuted = true)
        assertThat(multi).containsNoneOf(SelectionAction.MUTE, SelectionAction.UNMUTE)
    }

    @Test
    fun `overflow pin entry flips to unpin when everything selected is pinned`() {
        assertThat(SelectionBarLayout.overflowActions(allSelectedPinned = true, singleThread = false))
            .containsExactly(SelectionAction.UNPIN, SelectionAction.SELECT_ALL)
            .inOrder()
    }

    @Test
    fun `no action appears both inline and in the overflow`() {
        val overflow = SelectionBarLayout.overflowActions(allSelectedPinned = false, singleThread = true)
        assertThat(SelectionBarLayout.inlineActions.intersect(overflow.toSet())).isEmpty()
    }

    // endregion
}
