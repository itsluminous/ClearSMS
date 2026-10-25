package app.clearsms.ui.components

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import org.junit.Test
import java.io.File

/**
 * Source contract for per-message text direction (GitHub #139): every surface
 * that shows message text goes through the ONE mechanism,
 * [MessageTextDirection.style], so the bubble, the composer and the list rows
 * cannot drift apart - and the mechanism changes the TEXT only. Bubble sides,
 * the time/tick/SIM row and the layout direction of the UI are untouched.
 *
 * Source-level because the project has no Compose UI test harness.
 */
class MessageTextDirectionContractTest {
    private val srcRoot = File("src/main/kotlin/app/clearsms")

    private fun source(path: String): String = File(srcRoot, path).readText()

    private val conversation = source("ui/conversation/ConversationScreen.kt")
    private val composer = source("ui/components/MessageComposerBar.kt")

    /** Every surface showing a message body or snippet, by source file. */
    private val messageTextSurfaces =
        listOf(
            "ui/conversation/ConversationScreen.kt",
            "ui/components/MessageComposerBar.kt",
            "ui/inbox/InboxScreen.kt",
            "ui/search/SearchScreen.kt",
            "ui/inbox/ArchivedScreen.kt",
            "ui/inbox/BinScreen.kt",
        )

    @Test
    fun `every message-text surface merges the shared direction style`() {
        for (path in messageTextSurfaces) {
            assertWithMessage("$path must merge MessageTextDirection.style into its message text style")
                .that(source(path))
                .contains(".merge(MessageTextDirection.style)")
        }
    }

    @Test
    fun `the direction mechanism lives in exactly one place`() {
        // No surface hand-rolls its own TextDirection: the platform bidi
        // decision is made once, in MessageTextDirection, and merged in.
        val offenders =
            srcRoot
                .walkTopDown()
                .filter { it.isFile && it.extension == "kt" && it.name != "MessageTextDirection.kt" }
                .filter { it.readText().contains("import androidx.compose.ui.text.style.TextDirection") }
                .map { it.relativeTo(srcRoot).path }
                .toList()
        assertWithMessage("only MessageTextDirection may choose a TextDirection").that(offenders).isEmpty()
        val mechanism = source("ui/components/MessageTextDirection.kt")
        assertThat(mechanism).contains("textDirection = TextDirection.Content")
        assertThat(mechanism).contains("textAlign = TextAlign.Start")
    }

    @Test
    fun `the bubble body and its text-selection twin share one merged style`() {
        // Both branches render from the same local, so entering text-selection
        // mode (issue #103) cannot change the direction or re-flow a glyph.
        assertThat(conversation).contains("val bodyStyle = MaterialTheme.typography.bodyLarge.merge(MessageTextDirection.style)")
        assertThat(conversation.windowAfter("TextSelectionBody(\n", lines = 5)).contains("style = bodyStyle")
        assertThat(conversation.windowAfter("LinkifiedBodyText(\n", lines = 5)).contains("style = bodyStyle")
        // The hidden layout twin and the field inside TextSelectionBody both
        // take the same `style`, so the field's paragraph direction matches
        // the text whose box it is stretched to.
        val selectionBody = conversation.substringAfter("private fun TextSelectionBody(")
        assertThat(selectionBody).contains("style = style,")
        assertThat(selectionBody).contains("textStyle = style.copy(color = color)")
    }

    @Test
    fun `the composer field adopts the direction of what is typed`() {
        // The field's textStyle - not a wrapper, not the placeholder - merges
        // the mechanism, so the paragraph flips as the text does.
        val field = composer.substringAfter("BasicTextField(\n").substringBefore("lineLimits =")
        assertThat(field).contains("textStyle =")
        assertThat(field).contains(".merge(MessageTextDirection.style)")
    }

    @Test
    fun `bubble sides and chrome are decided by outgoing-ness, never by text`() {
        // Outgoing right, incoming left - exactly as before #139.
        assertThat(conversation).contains("val alignment = if (item.outgoing) Alignment.CenterEnd else Alignment.CenterStart")
        assertThat(conversation).contains("horizontalAlignment = if (item.outgoing) Alignment.End else Alignment.Start")
        // The time / tick / SIM row stays in the bubble's end corner.
        assertThat(conversation).contains("modifier = Modifier.align(Alignment.End).padding(top = 2.dp)")
        // No forced text alignment anywhere in the conversation or composer:
        // Start is the only alignment, resolved per paragraph by the mechanism.
        for ((name, text) in listOf("ConversationScreen" to conversation, "MessageComposerBar" to composer)) {
            assertWithMessage("$name must not force a text alignment").that(text).doesNotContain("TextAlign.End")
            assertWithMessage("$name must not force a text alignment").that(text).doesNotContain("TextAlign.Left")
            assertWithMessage("$name must not force a text alignment").that(text).doesNotContain("TextAlign.Right")
        }
    }

    @Test
    fun `no surface flips the UI's layout direction`() {
        // Per-message CONTENT direction only: the app never provides its own
        // LocalLayoutDirection, so the UI language's direction governs the
        // chrome exactly as before.
        val offenders =
            srcRoot
                .walkTopDown()
                .filter { it.isFile && it.extension == "kt" }
                .filter { it.readText().contains("LocalLayoutDirection provides") }
                .map { it.relativeTo(srcRoot).path }
                .toList()
        assertThat(offenders).isEmpty()
    }

    private fun String.windowAfter(
        marker: String,
        lines: Int,
    ): String {
        val start = indexOf(marker)
        assertWithMessage("marker not found: $marker").that(start).isAtLeast(0)
        return substring(start).lineSequence().take(lines + 1).joinToString("\n")
    }
}
