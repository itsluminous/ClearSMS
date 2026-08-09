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
}
