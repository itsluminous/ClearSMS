package app.clearsms.ui.components

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Pure state logic of the compose box's expand-to-full-screen toggle: what
 * each state shows, how the back gesture behaves, the inset-derived height
 * decision, and the draft-adoption rule that protects in-flight typing.
 * No Compose harness - [ComposerExpansion] is plain Kotlin by design.
 */
class ComposerExpansionTest {
    @Test
    fun `collapsed shows the full send tooling and caps the field at four lines`() {
        val a = ComposerExpansion.affordances(expanded = false)
        assertThat(a.attachVisible).isTrue()
        assertThat(a.attachmentsRowVisible).isTrue()
        assertThat(a.simIndicatorVisible).isTrue()
        assertThat(a.sendVisible).isTrue()
        // Collapsed, the top app bar already names the recipient.
        assertThat(a.recipientHeaderVisible).isFalse()
        assertThat(a.fieldFillsHeight).isFalse()
        assertThat(a.fieldMaxLines).isEqualTo(ComposerExpansion.COLLAPSED_MAX_LINES)
    }

    @Test
    fun `expanded hides attach - sim - send but keeps the recipient visible`() {
        val a = ComposerExpansion.affordances(expanded = true)
        assertThat(a.attachVisible).isFalse()
        assertThat(a.attachmentsRowVisible).isFalse()
        assertThat(a.simIndicatorVisible).isFalse()
        assertThat(a.sendVisible).isFalse()
        // The full-screen field covers the top bar: the user must still see
        // who they are writing to.
        assertThat(a.recipientHeaderVisible).isTrue()
        assertThat(a.fieldFillsHeight).isTrue()
        // Unbounded lines in a fixed-height field: long text scrolls inside.
        assertThat(a.fieldMaxLines).isEqualTo(Int.MAX_VALUE)
    }

    @Test
    fun `back collapses first while expanded and is never swallowed collapsed`() {
        // Send is hidden while expanded, so leaving expansion must be cheap:
        // the system back gesture collapses.
        assertThat(ComposerExpansion.backCollapsesFirst(expanded = true)).isTrue()
        // Already collapsed, back must leave the screen as always.
        assertThat(ComposerExpansion.backCollapsesFirst(expanded = false)).isFalse()
    }

    @Test
    fun `the icon toggles BOTH directions - expand then collapse via the icon, not only via back`() {
        // Issue #30: after maximising, the shrink icon must undo it - the
        // same transition rule both ways, so collapse-by-icon can never be
        // the odd one out again.
        assertThat(ComposerExpansion.toggled(expanded = false)).isTrue()
        assertThat(ComposerExpansion.toggled(expanded = true)).isFalse()
        // A full icon round trip lands back where it started.
        assertThat(ComposerExpansion.toggled(ComposerExpansion.toggled(expanded = false))).isFalse()
    }

    @Test
    fun `collapsed field is a bounded window that scrolls - never single-line, never unbounded`() {
        // Issue #30 finding: the field IS scrollable (a height-capped Compose
        // text field pans by drag/fling); the reporter's upward drags were
        // captured by the cursor-handle gesture, not by missing scrolling -
        // and the value-based field clamps THAT gesture to the visible window
        // (fixed by the state-based field, see the source contract). What
        // the app owns is the window shape: more than one line (so there is
        // something to scroll within) and strictly bounded (so a long draft
        // can never swallow the conversation).
        val collapsed = ComposerExpansion.affordances(expanded = false).fieldMaxLines
        assertThat(collapsed).isEqualTo(ComposerExpansion.COLLAPSED_MAX_LINES)
        assertThat(collapsed).isGreaterThan(1)
        assertThat(collapsed).isLessThan(Int.MAX_VALUE)
    }

    @Test
    fun `the expand toggle hides for an empty or single-line draft and appears from the second laid-out line`() {
        // Telegram convention: nothing to expand for while the text fits one
        // line. The decision is the REAL laid-out line count (font scale,
        // emoji, CJK, soft wraps included), never a character count.
        assertThat(ComposerExpansion.toggleVisible(laidOutLineCount = 1, expanded = false)).isFalse()
        assertThat(ComposerExpansion.toggleVisible(laidOutLineCount = 2, expanded = false)).isTrue()
        assertThat(ComposerExpansion.toggleVisible(laidOutLineCount = 7, expanded = false)).isTrue()
        assertThat(ComposerExpansion.TOGGLE_MIN_LINES).isEqualTo(2)
    }

    @Test
    fun `the 1 to 2 line boundary is exact both ways - typing reveals - deleting back to one line hides`() {
        // Reassessed as the user types and deletes: the same rule both ways,
        // with no dead zone that would leave a stale icon on a one-line draft
        // or hide it on a two-line one.
        val typing = listOf(1, 1, 2, 2, 3).map { ComposerExpansion.toggleVisible(it, expanded = false) }
        assertThat(typing).containsExactly(false, false, true, true, true).inOrder()
        val deleting = listOf(3, 2, 1).map { ComposerExpansion.toggleVisible(it, expanded = false) }
        assertThat(deleting).containsExactly(true, true, false).inOrder()
    }

    @Test
    fun `expanded - the shrink control stays visible whatever the line count`() {
        // The shrink icon is the only visible way back from full screen; a
        // one-line (or emptied) draft must never strand the user there.
        for (lines in listOf(1, 2, 40)) {
            assertThat(ComposerExpansion.toggleVisible(laidOutLineCount = lines, expanded = true)).isTrue()
        }
    }

    @Test
    fun `a layout pass with no result keeps the last line count - the icon cannot blink at the boundary`() {
        // Compose may call onTextLayout with a momentarily unavailable
        // result; treating that as "one line" would flash the icon off
        // between two multi-line layouts.
        assertThat(ComposerExpansion.nextLaidOutLineCount(current = 3, reported = null)).isEqualTo(3)
        // A real count always wins, in both directions.
        assertThat(ComposerExpansion.nextLaidOutLineCount(current = 3, reported = 1)).isEqualTo(1)
        assertThat(ComposerExpansion.nextLaidOutLineCount(current = 1, reported = 2)).isEqualTo(2)
        // An empty field lays out one (empty) line - never zero.
        assertThat(ComposerExpansion.nextLaidOutLineCount(current = 2, reported = 0)).isEqualTo(1)
    }

    @Test
    fun `expanded height sits above the live keyboard - the IME inset wins while open`() {
        // Typical open keyboard: IME inset subsumes the nav bar.
        assertThat(ComposerExpansion.expandedBottomInsetPx(imeBottomPx = 900, navigationBarsBottomPx = 84))
            .isEqualTo(900)
    }

    @Test
    fun `keyboard dismissed while expanded - the box stays expanded down to the nav bar`() {
        // The user closed the IME to review a long message; the field grows
        // into the freed space instead of the layout collapsing under them.
        assertThat(ComposerExpansion.expandedBottomInsetPx(imeBottomPx = 0, navigationBarsBottomPx = 84))
            .isEqualTo(84)
        // Button-nav / no-inset devices degrade to zero, never negative.
        assertThat(ComposerExpansion.expandedBottomInsetPx(imeBottomPx = 0, navigationBarsBottomPx = 0))
            .isEqualTo(0)
    }

    @Test
    fun `external draft adoption - a stale echo can never clobber in-flight typing`() {
        // The ViewModel's flow echoes keystrokes back a frame late: while
        // both sides are non-empty and merely different, the field wins.
        assertThat(ComposerExpansion.shouldAdoptExternalDraft(external = "hell", field = "hello")).isFalse()
        // Identical values are a no-op (no cursor jump).
        assertThat(ComposerExpansion.shouldAdoptExternalDraft(external = "hello", field = "hello")).isFalse()
    }

    @Test
    fun `external draft adoption - send-consume - failure-restore and persisted load all cross an empty boundary`() {
        // Send/schedule consumed the draft: the field clears.
        assertThat(ComposerExpansion.shouldAdoptExternalDraft(external = "", field = "hello")).isTrue()
        // A failed send restores the body into the cleared field, and the
        // persisted per-thread draft loads into an untouched field.
        assertThat(ComposerExpansion.shouldAdoptExternalDraft(external = "hello", field = "")).isTrue()
        // Both empty: nothing to do.
        assertThat(ComposerExpansion.shouldAdoptExternalDraft(external = "", field = "")).isFalse()
    }
}
