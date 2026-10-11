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
 * are kept apart on the bubble, that in text mode the body becomes a
 * READ-ONLY text field with the whole message pre-selected (so the handles
 * are on screen at once - no third long-press), that it keeps the exact look
 * of the Text it replaces, that nothing but the body is selectable, that the
 * exit path unwinds one level, and that the selection bar's Copy reads the
 * field's LIVE selection (the screen owns the field) rather than copying the
 * whole message behind a narrowed highlight.
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
    fun `in text mode the body is a read-only text field with the whole message pre-selected`() {
        // SelectionContainer only creates a selection from a long-press within
        // the text and cannot be seeded, so it cost a THIRD long-press before
        // the handles appeared. A TextFieldState whose selection is already
        // non-collapsed draws both handles the moment the field has focus.
        assertThat(bubble).containsMatch(
            """if \(textSelectionField != null\) \{[\s\S]*?TextSelectionBody\(\s*body = item\.body,\s*fieldState = textSelectionField,""",
        )
        // The bubble is in text mode exactly when the screen handed it a field.
        assertThat(bubble).contains("val textSelecting = textSelectionField != null")
        val field = bubble.substringAfter("private fun TextSelectionBody(")
        assertThat(field).doesNotContain("remember(body)")
        assertThat(field).containsMatch("""BasicTextField\(\s*state = fieldState,\s*readOnly = true,""")
        // Focus is what makes the handles render; it is requested on entry,
        // and read-only means that focus opens no keyboard and draws no caret.
        assertThat(field).contains("LaunchedEffect(fieldState) { focusRequester.requestFocus() }")
        assertThat(field).contains(".focusRequester(focusRequester)")
        assertThat(field).doesNotContain("KeyboardOptions")
        assertThat(field).doesNotContain("SoftwareKeyboardController")
        // The screen no longer uses SelectionContainer at all; the details
        // dialog and the rule wizard keep it - their text has no handles problem.
        assertThat(screen).doesNotContain("SelectionContainer")
        for (path in listOf("ui/conversation/MessageDetailsDialog.kt", "ui/rules/RuleWizardScreen.kt")) {
            assertThat(File("src/main/kotlin/app/clearsms/$path").readText()).contains("SelectionContainer {")
        }
    }

    @Test
    fun `visual parity - the field is stretched over the undrawn original Text, same style and colour`() {
        // The ORIGINAL body Text is still laid out with the same style and
        // colour and only its drawing is suppressed; the field fills exactly
        // that box, which also defeats a text field's ten-character minimum
        // width. Same width + same style = same line breaks = no glyph moves.
        val field = bubble.substringAfter("private fun TextSelectionBody(")
        val ghost = field.substringAfter("Box {").substringBefore("BasicTextField(")
        assertThat(ghost).containsMatch("""Text\(\s*text = body,\s*style = style,\s*color = color,\s*modifier = Modifier\.drawWithContent \{ \},""")
        val fieldCall = field.substringAfter("BasicTextField(")
        assertThat(fieldCall).contains("textStyle = style.copy(color = color),")
        assertThat(fieldCall).contains(".matchParentSize()")
        // The bubble passes the very style and colour the Text used to get.
        assertThat(bubble).containsMatch(
            """TextSelectionBody\(\s*body = item\.body,\s*fieldState = textSelectionField,\s*style = MaterialTheme\.typography\.bodyLarge,\s*color = textColor,""",
        )
    }

    @Test
    fun `accessibility - the field announces nothing, the undrawn Text is still read as message text`() {
        // A text field exposes EditableText, which Android reports as an
        // EditText ("edit box"). Clearing the field's semantics leaves a
        // screen reader exactly the node it had before: the body Text.
        val field = bubble.substringAfter("private fun TextSelectionBody(")
        assertThat(field.substringAfter("BasicTextField(")).contains(".clearAndSetSemantics { }")
        assertThat(field.substringAfter("Box {").substringBefore("BasicTextField(")).doesNotContain("semantics")
    }

    @Test
    fun `exit path - Back inside the focused field is routed to the dispatcher, not swallowed`() {
        // A focused field with a selection consumes KEYCODE_BACK to collapse
        // its selection; that would cost the user an extra press. It is routed
        // to the OnBackPressedDispatcher so the screen's BackHandler unwinds
        // text mode exactly as before.
        val field = bubble.substringAfter("private fun TextSelectionBody(")
        assertThat(field).contains("if (event.key != Key.Back) return@onPreviewKeyEvent false")
        assertThat(field).contains("if (event.type == KeyEventType.KeyUp) backDispatcher?.onBackPressed()")
    }

    @Test
    fun `only the body is selectable - time label, status lines, OTP button and detail card stay outside`() {
        // Exactly one text-mode body in the bubble, and it closes before the
        // time-label Row begins: timestamps cannot be swept into a copy.
        assertThat(bubble.split("TextSelectionBody(").size - 1).isEqualTo(2) // the call and its declaration
        val selectable = bubble.substringAfter("TextSelectionBody(").substringBefore("LinkifiedBodyText(")
        assertThat(selectable).doesNotContain("item.timeLabel")
        assertThat(selectable).doesNotContain("DeliveryTickIcon")
        assertThat(selectable).doesNotContain("action_copy_otp")
        assertThat(selectable).doesNotContain("ParsedDetailCard")
        assertThat(bubble.indexOf("TextSelectionBody(")).isLessThan(bubble.indexOf("text = item.timeLabel"))
        // The linkified body is NOT the text-mode rendering: links are inert in
        // text mode so a tap cannot open a page instead of clearing the selection.
        assertThat(selectable).doesNotContain("onLinkClick")
        assertThat(bubble.substringAfter("private fun TextSelectionBody(")).doesNotContain("LinkifiedBodyText")
    }

    @Test
    fun `the non-text-mode path still renders the linkified body, byte for byte`() {
        // Only the in-text-mode rendering changed. Outside it the body is the
        // same LinkifiedBodyText call with the same arguments, so tappable
        // links, phone numbers and UPI links are untouched.
        assertThat(bubble).contains(
            """
            |                                } else {
            |                                    LinkifiedBodyText(
            |                                        body = item.body,
            |                                        onLinkClick = onLinkClick,
            |                                        style = MaterialTheme.typography.bodyLarge,
            |                                        color = textColor,
            |                                    )
            |                                }
            """.trimMargin(),
        )
        assertThat(bubble.split("LinkifiedBodyText(").size - 1).isEqualTo(1)
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
    fun `the screen owns the text-mode field, keyed on the message, and hands it to bubble and bar`() {
        // One TextFieldState per entry into text mode, created by the screen
        // from the body on screen - the bubble no longer remembers its own,
        // because the bar could not have read a selection held there.
        assertThat(screen).contains("val textSelectionId = selectionMode.textSelectionId")
        assertThat(screen).containsMatch(
            """val textSelectionField =\s*remember\(textSelectionId, textSelectionBody\) \{ textSelectionBody\?\.let\(TextSelectionField::state\) \}""",
        )
        assertThat(screen).contains("textSelectionField = textSelectionField?.takeIf { selectionMode.isTextSelecting(item.id) },")
        assertThat(screen.split("TextSelectionField.state(").size - 1).isEqualTo(0) // only via the reference above
        assertThat(screen).contains("textSelectionField = textSelectionField,") // into the bar
    }

    @Test
    fun `in text mode the bar's Copy reads the field's LIVE selection, labelled truthfully, disabled when collapsed`() {
        val bar = screen.substringAfter("private fun ConversationSelectionBar(").substringBefore("private fun DateSeparator(")
        val copy = bar.substringAfter("MessageSelectionAction.COPY -> {").substringBefore("MessageSelectionAction.DELETE -> {")
        // Derived from the field's current text + selection, never from the
        // message body alone; reading them in composition is what re-labels
        // the button as the handles move.
        assertThat(copy).contains("TextSelectionField.copyAction(textSelectionField.text, textSelectionField.selection)")
        assertThat(copy).contains("label = stringResource(TextSelectionField.labelRes(textCopy)),")
        assertThat(copy).contains("enabled = textCopy.text != null,")
        assertThat(copy).contains("onClick = { textCopy.text?.let(onCopySelectedText) },")
        // Outside text mode the whole-selection Copy is byte for byte what it was.
        assertThat(copy).contains(
            "SelectionActionSpec(stringResource(R.string.action_copy_message), Icons.Outlined.ContentCopy, onCopy)",
        )
        // The disabled state reaches both placements of the action.
        assertThat(bar).containsMatch("""TooltipIconButton\(\s*label = spec\.label,\s*onClick = spec\.onClick,\s*icon = spec\.icon,\s*enabled = spec\.enabled,""")
        assertThat(bar).containsMatch("""DropdownMenuItem\([\s\S]*?enabled = spec\.enabled,""")
        // The screen's handler copies that exact text and leaves selection
        // mode, as a whole-message Copy does.
        val handler = screen.substringAfter("onCopySelectedText = { text ->").substringBefore("},")
        assertThat(handler).contains("clipboard.setText(AnnotatedString(text))")
        assertThat(handler).contains("viewModel.exitSelection()")
        assertThat(handler).doesNotContain("selectedText")
    }

    @Test
    fun `the bar names the mode and the bubble's long-press label says what the second press does`() {
        assertThat(DefaultStrings.ui).contains("<string name=\"action_select_text\">Select text</string>")
        assertThat(DefaultStrings.ui).contains("<string name=\"action_copy_selected_text\">Copy selected text</string>")
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
