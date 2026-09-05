package app.clearsms.ui.components

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.io.File

/**
 * Source contract: there is exactly ONE compose bar. Both the conversation
 * screen and the new-conversation screen must render [MessageComposerBar]
 * (text field + SIM indicator + send with long-press schedule) rather than
 * a private variant, so send affordances can never diverge between them.
 */
class ComposerBarContractTest {
    private val srcRoot = File("src/main/kotlin/app/clearsms")

    private fun source(path: String): String = File(srcRoot, path).readText()

    @Test
    fun `conversation screen renders the shared composer bar`() {
        val screen = source("ui/conversation/ConversationScreen.kt")
        assertThat(screen).contains("MessageComposerBar(")
        // The pre-extraction private composer must not resurface.
        assertThat(screen).doesNotContain("fun ReplyComposer")
    }

    @Test
    fun `new-conversation screen renders the shared composer bar`() {
        val screen = source("ui/composemsg/ComposeMessageScreen.kt")
        assertThat(screen).contains("MessageComposerBar(")
        // Parity means the same schedule entry point too.
        assertThat(screen).contains("ScheduleTimePicker(")
        // The old send FAB (no SIM, no schedule) must not resurface.
        assertThat(screen).doesNotContain("ExtendedFloatingActionButton")
    }

    @Test
    fun `only the shared component defines a send surface with long-press schedule`() {
        val definitions =
            srcRoot
                .walkTopDown()
                .filter { it.extension == "kt" }
                .filter { it.readText().contains("fun MessageComposerBar(") }
                .map { it.relativeTo(srcRoot).path }
                .toList()
        assertThat(definitions).containsExactly("ui/components/MessageComposerBar.kt")
    }

    @Test
    fun `sim glyph is the plain outline - the dotted stock icon must not resurface`() {
        // GitHub #7: the stock SimCard icon's chip-contact dots made the slot
        // digit unreadable. The indicator must draw the bare outline plus the
        // digit, nothing else.
        val bar = source("ui/components/MessageComposerBar.kt")
        assertThat(bar).doesNotContain("Icons.Outlined.SimCard")
        // No import of the stock icon either (KDoc may still NAME it as the
        // thing this glyph replaced - that is documentation, not usage).
        assertThat(bar.lines().filter { it.startsWith("import ") && it.contains("SimCard") }).isEmpty()
        assertThat(bar).contains("SimOutlineGlyph")
        // Exactly one path in the glyph: the outline. A second path (or a
        // fill) would be where dots/contacts creep back in.
        val glyph = bar.substringAfter("SimOutlineGlyph: ImageVector")
        assertThat(Regex("""\bpath\(""").findAll(glyph).count()).isEqualTo(1)
        assertThat(glyph).doesNotContain("fill =")
    }
}
