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
