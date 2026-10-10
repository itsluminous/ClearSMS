package app.clearsms.ui.conversation

import app.clearsms.testing.DefaultStrings
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import org.junit.Test
import java.io.File

/**
 * Source contract for partial text selection (issue #103) - the repo's
 * pattern for behaviour that has no Compose UI harness (see
 * `MessageDetailsMenuContractTest`). Pins how the two long-press meanings
 * are kept apart on the bubble, that the body is wrapped in the SAME
 * SelectionContainer the details dialog and rule wizard use, that nothing
 * but the body is selectable, and that the exit path unwinds one level.
 */
class PartialTextSelectionContractTest {
    private val screen = File("src/main/kotlin/app/clearsms/ui/conversation/ConversationScreen.kt").readText()
    private val viewModel = File("src/main/kotlin/app/clearsms/ui/conversation/ConversationViewModel.kt").readText()
    private val bubble = screen.substringAfter("private fun MessageBubble(").substringBefore("private fun MessageMetadataLine(")

    @Test
    fun `the bubble routes its long-press through the pure state machine, not straight into enterSelection`() {
        assertThat(screen).contains("onLongClick = { viewModel.longPressMessage(item.id, selectableText) }")
        assertThat(screen).contains("ConversationSelectionMode.hasSelectableText(item.body, item.message?.mmsStatus)")
        assertThat(viewModel).contains("modeState.update { it.longPress(messageId, selectableText) }")
        // The old direct entry point is gone from the ViewModel: there is ONE
        // long-press routine, so the two meanings cannot drift apart.
        assertThat(viewModel).doesNotContain("fun enterSelection(")
    }

    @Test
    fun `gesture separation - a bubble in text mode has NO combinedClickable, every other bubble keeps it`() {
        // Clickable and text-selectable are mutually exclusive on one bubble,
        // so Compose's selection gestures never compete with the row's
        // tap / long-press. Exactly one combinedClickable, inside the
        // `if (textSelecting) Modifier else ...` branch.
        assertWithMessage("combinedClickable must be gated on textSelecting")
            .that(bubble)
            .containsMatch("""if \(textSelecting\) \{\s*Modifier\s*\} else \{\s*Modifier\.combinedClickable\(""")
        assertThat(bubble.split("combinedClickable(").size - 1).isEqualTo(1)
    }

    @Test
    fun `the body is wrapped in SelectionContainer - the same mechanism as the details dialog and rule wizard`() {
        assertThat(screen).contains("import androidx.compose.foundation.text.selection.SelectionContainer")
        assertThat(bubble).containsMatch("""if \(textSelecting\) \{[\s\S]*?SelectionContainer \{\s*Text\(\s*text = item\.body,""")
        // Same import, same composable, in the two places that already had it.
        for (path in listOf("ui/conversation/MessageDetailsDialog.kt", "ui/rules/RuleWizardScreen.kt")) {
            assertThat(File("src/main/kotlin/app/clearsms/$path").readText()).contains("SelectionContainer {")
        }
    }

    @Test
    fun `only the body is selectable - time label, status lines, OTP button and detail card stay outside`() {
        // Exactly one SelectionContainer in the bubble, and it closes before
        // the time-label Row begins: timestamps cannot be swept into a copy.
        assertThat(bubble.split("SelectionContainer {").size - 1).isEqualTo(1)
        val selectable = bubble.substringAfter("SelectionContainer {").substringBefore("LinkifiedBodyText(")
        assertThat(selectable).doesNotContain("item.timeLabel")
        assertThat(selectable).doesNotContain("DeliveryTickIcon")
        assertThat(selectable).doesNotContain("action_copy_otp")
        assertThat(selectable).doesNotContain("ParsedDetailCard")
        assertThat(bubble.indexOf("SelectionContainer {")).isLessThan(bubble.indexOf("text = item.timeLabel"))
        // The linkified body is NOT inside the container: links are inert in
        // text mode so a tap cannot open a page instead of clearing the selection.
        assertThat(selectable).doesNotContain("onLinkClick")
    }

    @Test
    fun `exit path - Back and Close unwind text mode first and keep the message selection`() {
        assertThat(screen).contains("BackHandler(enabled = selectionMode.textSelecting) { viewModel.exitTextSelection() }")
        assertThat(screen).contains("BackHandler(enabled = selection.active && !selectionMode.textSelecting) { viewModel.exitSelection() }")
        assertThat(screen).contains("onClick = if (textSelecting) onExitTextSelection else onClose,")
        assertThat(viewModel).contains("fun exitTextSelection()")
        assertThat(viewModel).contains("modeState.update { it.exitTextSelection() }")
    }

    @Test
    fun `the bar names the mode and the bubble's long-press label says what the second press does`() {
        assertThat(DefaultStrings.ui).contains("<string name=\"action_select_text\">Select text</string>")
        val bar = screen.substringAfter("private fun ConversationSelectionBar(").substringBefore("private fun DateSeparator(")
        assertThat(bar).contains("stringResource(R.string.action_select_text)")
        assertThat(bar).contains("stringResource(R.string.selection_count, selection.count)")
        // Accessibility: the custom long-press action is relabelled, never removed.
        assertThat(bubble).contains("if (offersTextSelection) R.string.action_select_text else R.string.inbox_row_actions")
        assertThat(screen).contains("ConversationSelectionMode.LongPress.SELECT_TEXT")
    }

    @Test
    fun `the selection bar's configurable action order is untouched`() {
        // No new MessageSelectionAction: text selection is a gesture on the
        // bubble, not a bar action, so the stored order (#72) needs no migration.
        val layout = File("src/main/kotlin/app/clearsms/ui/conversation/ConversationSelectionBarLayout.kt").readText()
        assertThat(layout).doesNotContain("SELECT_TEXT")
        assertThat(MessageSelectionAction.entries.map { it.name })
            .containsExactly("COPY", "DELETE", "MORE_DETAILS", "FORWARD", "SHARE", "SELECT_ALL", "COPY_OTP", "ADD_RULE")
            .inOrder()
    }
}
