package app.clearsms.ui.components

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
}
