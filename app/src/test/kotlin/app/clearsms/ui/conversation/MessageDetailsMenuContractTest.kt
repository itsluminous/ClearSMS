package app.clearsms.ui.conversation

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.io.File
import app.clearsms.testing.DefaultStrings

/**
 * Contract for the "More details" entry (issue #44). Source-level contracts
 * (the repo deliberately has no Compose UI test infrastructure, same style
 * as `UnreadToggleContractTest`) plus the layout-object gating: the item
 * sits INLINE by default (issue #61 moved it into Forward's slot), appears
 * ONLY for a single selection, and the dialog is copyable, scrollable and
 * honest about delivery.
 */
class MessageDetailsMenuContractTest {
    private fun source(path: String) = File("src/main/kotlin/app/clearsms/$path").readText()

    @Test
    fun `more details is inline by default, gated to a single selection, with forward in the overflow`() {
        // Issue #61: details took Forward's slot - one tap less for the
        // sent / received / delivered times people select a message for.
        val single = ConversationSelectionBarLayout.resolveDefault(singleMessage = true, hasOtp = false)
        assertThat(single.inline)
            .containsExactly(
                MessageSelectionAction.COPY,
                MessageSelectionAction.DELETE,
                MessageSelectionAction.MORE_DETAILS,
            ).inOrder()
        assertThat(single.inline).doesNotContain(MessageSelectionAction.FORWARD)
        assertThat(single.overflow).contains(MessageSelectionAction.FORWARD)
        // Several messages at once have no sensible details rendering: the
        // action is hidden (not disabled) and Forward moves up - no hole.
        val multi = ConversationSelectionBarLayout.resolveDefault(singleMessage = false, hasOtp = false)
        assertThat(multi.inline + multi.overflow).doesNotContain(MessageSelectionAction.MORE_DETAILS)
        assertThat(multi.inline).hasSize(ConversationSelectionBarLayout.INLINE_SLOTS)
        assertThat(MessageSelectionAction.MORE_DETAILS.appliesTo(singleMessage = false, hasOtp = true)).isFalse()
        assertThat(MessageSelectionAction.MORE_DETAILS.appliesTo(singleMessage = true, hasOtp = false)).isTrue()
    }

    @Test
    fun `the screen renders every action from one spec and opens the details dialog`() {
        val screen = source("ui/conversation/ConversationScreen.kt")
        assertThat(screen).contains("MessageSelectionAction.MORE_DETAILS ->")
        assertThat(screen).contains("stringResource(R.string.action_message_details)")
        assertThat(screen).contains("MessageDetailsDialog(")
        // The name shown is the top bar's resolution chain, not a new one.
        assertThat(screen).contains("resolvedName = state.title")
        // Inline and overflow render from the SAME resolved split and the
        // SAME per-action spec, so an action placed anywhere by the user's
        // order renders there and nothing can fall through an `else`.
        assertThat(screen).contains("ConversationSelectionBarLayout.resolve(")
        assertThat(screen).contains("order = state.selectionActionOrder,")
        assertThat(screen).contains("actions.inline.forEach { action ->")
        assertThat(screen).contains("actions.overflow.forEach { action ->")
        val bar = screen.substringAfter("private fun ConversationSelectionBar(").substringBefore("private fun DateSeparator(")
        assertThat(bar).doesNotContain("else -> Unit")
        for (action in MessageSelectionAction.entries) {
            assertThat(bar).contains("MessageSelectionAction.${action.name} ->")
        }
        // Details opens from the single selected message, as before.
        assertThat(screen).contains("detailsMessage = singleSelected?.message")
    }

    @Test
    fun `dialog values are copyable, the column scrolls, and rows come from the pure mapper`() {
        val dialog = source("ui/conversation/MessageDetailsDialog.kt")
        assertThat(dialog).contains("SelectionContainer")
        assertThat(dialog).contains("verticalScroll(rememberScrollState())")
        assertThat(dialog).contains("MessageDetails.rowsFor(")
    }

    @Test
    fun `delivery wording is short and honest - Yes, Unknown, never Yes for MMS, no time invented`() {
        val strings = DefaultStrings.ui
        // Confirmed without a recorded time: exactly "Yes" - no carrier story.
        assertThat(strings).contains("<string name=\"message_details_delivered_confirmed\">Yes</string>")
        // No report at all: a one-word honest "Unknown".
        assertThat(strings).contains("<string name=\"message_details_delivered_unknown\">Unknown</string>")
        // MMS: says Unknown (with why, briefly) and never "Yes".
        assertThat(strings).contains(
            "<string name=\"message_details_delivered_mms\">Unknown - not supported for MMS</string>",
        )
        assertThat(strings).doesNotContain("<string name=\"message_details_delivered_mms\">Yes")
        val dialog = source("ui/conversation/MessageDetailsDialog.kt")
        // A recorded acknowledgement is shown as the bare time, nothing appended.
        assertThat(dialog).contains("row.acknowledgedAtMs != null ->")
        assertThat(dialog).contains("preciseTimestampLabel(row.acknowledgedAtMs, is24Hour)\n")
        assertThat(dialog).doesNotContain("preciseTimestampLabel(row.acknowledgedAtMs, is24Hour) +")
        // MMS can only ever render through the unsupported string.
        assertThat(dialog).containsMatch("""DeliveryKnowledge\.UNSUPPORTED_MMS ->\s*\{?\s*R\.string\.message_details_delivered_mms""")
    }

    @Test
    fun `the explanatory strings are gone - no not-reported sent row, no delivery-report note`() {
        val strings = DefaultStrings.ui
        assertThat(strings).doesNotContain("message_details_sent_unknown")
        assertThat(strings).doesNotContain("message_details_delivered_at_note")
        assertThat(strings).doesNotContain("not the carrier\\'s own timestamp")
        assertThat(strings).doesNotContain("no delivery time can be shown")
        assertThat(strings).doesNotContain("Not reported - the network")
        val dialog = source("ui/conversation/MessageDetailsDialog.kt")
        val mapper = source("ui/conversation/MessageDetails.kt")
        assertThat(dialog).doesNotContain("SentTimeUnknown")
        assertThat(mapper).doesNotContain("SentTimeUnknown")
        assertThat(dialog).doesNotContain("message_details_sent_unknown")
        assertThat(dialog).doesNotContain("message_details_delivered_at_note")
    }

    @Test
    fun `each details row merges label and value into one accessibility node`() {
        // A screen reader must hear "Delivered, Yes", never a bare "Yes".
        val dialog = source("ui/conversation/MessageDetailsDialog.kt")
        assertThat(dialog).contains("Column(modifier = Modifier.semantics(mergeDescendants = true) {})")
    }
}
