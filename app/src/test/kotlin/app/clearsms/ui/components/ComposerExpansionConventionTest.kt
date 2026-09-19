package app.clearsms.ui.components

import androidx.compose.ui.unit.dp
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.io.File

/**
 * Source contract (the ComposerBarContractTest pattern) for the
 * expand-to-full-screen compose box:
 *
 * 1. PARITY: the behaviour is implemented ONCE, inside the shared
 *    [MessageComposerBar] via [ComposerExpansion] - neither compose entry
 *    screen may grow its own expansion, and both must feed the bar the
 *    recipient identity it shows while expanded.
 * 2. Draft safety: the field state is a TextFieldValue in rememberSaveable
 *    (text + selection survive rotation) behind the empty-boundary
 *    adoption gate, and there is exactly ONE OutlinedTextField call so the
 *    field node (selection, internal scroll) survives every toggle.
 * 3. Back collapses first; insets are live, never an assumed height; the
 *    small toggle keeps distinct expand/collapse accessibility labels.
 */
class ComposerExpansionConventionTest {
    private val srcRoot = File("src/main/kotlin/app/clearsms")

    private fun source(path: String): String = File(srcRoot, path).readText()

    @Test
    fun `both compose entry points pass the recipient identity to the one shared bar`() {
        for (screen in listOf("ui/conversation/ConversationScreen.kt", "ui/composemsg/ComposeMessageScreen.kt")) {
            val text = source(screen)
            assertThat(text).contains("MessageComposerBar(")
            assertThat(text).contains("recipientLabel =")
        }
    }

    @Test
    fun `the expansion lives only in the shared bar - no screen may fork its own`() {
        // The bar (and its pure-logic object) are the ONLY places that may
        // reference the expansion machinery or the fullscreen glyphs.
        val allowed = setOf("ui/components/MessageComposerBar.kt", "ui/components/ComposerExpansion.kt")
        val offenders =
            srcRoot
                .walkTopDown()
                .filter { it.extension == "kt" }
                .filter { file ->
                    val text = file.readText()
                    text.contains("ComposerExpansion") || text.contains("OpenInFull") || text.contains("CloseFullscreen")
                }.map { it.relativeTo(srcRoot).path }
                .toList()
        assertThat(offenders).containsExactlyElementsIn(allowed)
    }

    @Test
    fun `the bar routes every visibility decision through the pure affordances`() {
        val bar = source("ui/components/MessageComposerBar.kt")
        assertThat(bar).contains("ComposerExpansion.affordances(expanded)")
        // Expansion survives rotation.
        assertThat(bar).contains("var expanded by rememberSaveable")
        // Each affordance is gated on the tested logic, not ad-hoc booleans.
        for (gate in listOf(
            "barState.attachVisible",
            "barState.attachmentsRowVisible",
            "barState.simIndicatorVisible",
            "barState.sendVisible",
            "barState.recipientHeaderVisible",
            "barState.fieldFillsHeight",
            "barState.fieldMaxLines",
        )) {
            assertThat(bar).contains(gate)
        }
    }

    @Test
    fun `draft text and selection ride TextFieldValue-Saver behind the adoption gate`() {
        val bar = source("ui/components/MessageComposerBar.kt")
        // Selection/cursor preserved across config changes...
        assertThat(bar).contains("rememberSaveable(stateSaver = TextFieldValue.Saver)")
        // ...and external draft changes only land across an empty boundary,
        // so a flow echo can never clobber typing (unit-tested rule).
        assertThat(bar).contains("ComposerExpansion.shouldAdoptExternalDraft(external = draft, field = fieldValue.text)")
        // ONE field node for both states: a second OutlinedTextField would
        // fork selection/scroll state on toggle.
        assertThat(Regex("""OutlinedTextField\(""").findAll(bar).count()).isEqualTo(1)
    }

    @Test
    fun `back collapses first and the expanded height derives from live insets`() {
        val bar = source("ui/components/MessageComposerBar.kt")
        assertThat(bar).contains("BackHandler(enabled = ComposerExpansion.backCollapsesFirst(expanded))")
        // Never an assumed keyboard height: the live IME and nav-bar insets
        // feed the unit-tested max() decision.
        assertThat(bar).contains("ComposerExpansion")
        assertThat(bar).contains("expandedBottomInsetPx(")
        assertThat(bar).contains("WindowInsets.ime.getBottom")
        assertThat(bar).contains("WindowInsets.navigationBars.getBottom")
    }

    @Test
    fun `the small toggle carries distinct expand and shrink accessibility labels`() {
        val bar = source("ui/components/MessageComposerBar.kt")
        assertThat(bar).contains("R.string.compose_expand")
        assertThat(bar).contains("R.string.compose_collapse")
        // One label feeds both the action label and the icon description.
        assertThat(bar).contains("onClickLabel = label")
        assertThat(bar).contains("contentDescription = label")
        val strings = File("src/main/res/values/strings_platform.xml").readText()
        assertThat(strings).contains("\"compose_expand\"")
        assertThat(strings).contains("\"compose_collapse\"")
    }

    @Test
    fun `the one toggle rides the field box in both states - never the top-of-screen header`() {
        // Issue #30: the shrink toggle used to live in the recipient header,
        // hard against the status bar, where the reporter's device (and the
        // maintainer's) delivered no taps to it - every in-window cause was
        // ruled out empirically, and the video shows 16/16 in-bounds taps
        // with zero press feedback, so that top band is contested by system
        // chrome. The toggle therefore anchors to the compose box's own
        // top-right corner in BOTH states (the expand icon there provably
        // works on the same device), which also means the finger that just
        // expanded finds the shrink control in the very spot it tapped.
        val bar = source("ui/components/MessageComposerBar.kt")
        // Exactly ONE call site (the definition adds the second occurrence).
        assertThat(Regex("""ExpandToggle\(""").findAll(bar).count()).isEqualTo(2)
        // The header block (recipient label) must stay toggle-free.
        val header = bar.substringAfter("barState.recipientHeaderVisible)").substringBefore("barState.attachmentsRowVisible")
        assertThat(header).doesNotContain("ExpandToggle(")
        // The call site rides the field's Box corner, unconditionally - a
        // state-gated `if (!expanded)` here is the regression shape.
        val callSite = bar.substringAfter("OutlinedTextField(").substringBefore("barState.simIndicatorVisible")
        assertThat(callSite).contains("ExpandToggle(")
        assertThat(callSite).contains("modifier = Modifier.align(Alignment.TopEnd)")
        assertThat(callSite).doesNotContain("if (!expanded)")
        assertThat(callSite).doesNotContain("if (expanded)")
        // Both directions route through the one pure, tested transition.
        assertThat(callSite).contains("expanded = expanded")
        assertThat(callSite).contains("onToggle = { expanded = ComposerExpansion.toggled(expanded) }")
    }

    @Test
    fun `the toggle is a real 48dp touch target built from the shared metrics`() {
        // The toggle overlaps the text field, and Compose's minimum-touch-
        // target EXPANSION ring loses hit-testing to the field's direct
        // hits - so the 48dp accessibility minimum must be the box itself,
        // derived (glyph + 2x padding), never hand-tuned numbers.
        assertThat(ComposerToggleMetrics.TouchTarget).isEqualTo(48.dp)
        assertThat(ComposerToggleMetrics.GlyphSize).isEqualTo(20.dp)
        assertThat(
            ComposerToggleMetrics.GlyphSize + ComposerToggleMetrics.GlyphPadding * 2,
        ).isEqualTo(ComposerToggleMetrics.TouchTarget)
        val bar = source("ui/components/MessageComposerBar.kt")
        val toggle = bar.substringAfter("private fun ExpandToggle").substringBefore("internal object ComposerToggleMetrics")
        assertThat(toggle).contains(".padding(ComposerToggleMetrics.GlyphPadding)")
        assertThat(toggle).contains("Modifier.size(ComposerToggleMetrics.GlyphSize)")
    }

    @Test
    fun `the collapsed field takes its bounded-scrollable window from the pure affordances`() {
        // Bug 1 (issue #30): the collapsed box's scrolling comes from being
        // a maxLines-BOUNDED text field (Compose pans such a window
        // internally - ScrollBy semantics). The bound must flow from the
        // tested affordances, and nothing may pin the field's height or
        // flatten it to a single line, which are the two shapes that would
        // genuinely clip a long draft without scrolling.
        val bar = source("ui/components/MessageComposerBar.kt")
        assertThat(bar).contains("maxLines = barState.fieldMaxLines")
        assertThat(bar).doesNotContain("singleLine = true")
        val fieldBox = bar.substringAfter("// ONE OutlinedTextField call").substringBefore("barState.simIndicatorVisible")
        assertThat(fieldBox).doesNotContain(".height(")
        assertThat(fieldBox).doesNotContain(".heightIn(")
    }
}
