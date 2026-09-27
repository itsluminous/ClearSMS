package app.clearsms.ui.conversation

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.io.File

/**
 * Contract for the "More details" entry (issue #44). Source-level contracts
 * (the repo deliberately has no Compose UI test infrastructure, same style
 * as `UnreadToggleContractTest`) plus the layout-object gating: the item
 * exists in the selection overflow, appears ONLY for a single selection,
 * and the dialog is copyable, scrollable and honest about delivery.
 */
class MessageDetailsMenuContractTest {
    private fun source(path: String) = File("src/main/kotlin/app/clearsms/$path").readText()

    @Test
    fun `more details exists in the overflow and is gated to single selection`() {
        val single = ConversationSelectionBarLayout.overflowActions(singleMessage = true, hasOtp = false)
        assertThat(single).contains(MessageSelectionAction.MORE_DETAILS)
        val multi = ConversationSelectionBarLayout.overflowActions(singleMessage = false, hasOtp = false)
        assertThat(multi).doesNotContain(MessageSelectionAction.MORE_DETAILS)
        // Never inline: it must not displace the copy/delete/forward trio.
        assertThat(ConversationSelectionBarLayout.inlineActions)
            .doesNotContain(MessageSelectionAction.MORE_DETAILS)
    }

    @Test
    fun `the screen renders the menu item and opens the details dialog`() {
        val screen = source("ui/conversation/ConversationScreen.kt")
        assertThat(screen).contains("MessageSelectionAction.MORE_DETAILS ->")
        assertThat(screen).contains("stringResource(R.string.action_message_details)")
        assertThat(screen).contains("MessageDetailsDialog(")
        // The name shown is the top bar's resolution chain, not a new one.
        assertThat(screen).contains("resolvedName = state.title")
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
        val strings = File("src/main/res/values/strings_ui.xml").readText()
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
        assertThat(dialog).contains("DeliveryKnowledge.UNSUPPORTED_MMS ->\n")
        assertThat(dialog).contains("R.string.message_details_delivered_mms")
    }

    @Test
    fun `the explanatory strings are gone - no not-reported sent row, no delivery-report note`() {
        val strings = File("src/main/res/values/strings_ui.xml").readText()
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
