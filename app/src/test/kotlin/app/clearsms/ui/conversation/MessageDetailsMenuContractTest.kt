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
    fun `delivery wording is honest - unknown without a report, unsupported for MMS, no time invented`() {
        val strings = File("src/main/res/values/strings_ui.xml").readText()
        assertThat(strings).contains("message_details_delivered_unknown")
        assertThat(strings).contains("message_details_delivered_mms")
        // A confirmed delivery whose report arrival was never recorded
        // still shows no time: none is invented.
        assertThat(strings).contains("no delivery time can be shown")
        // A recorded acknowledgement is shown WITH what it is: the report's
        // arrival on this phone, not the carrier's own timestamp.
        assertThat(strings).contains("message_details_delivered_at_note")
        assertThat(strings).contains("not the carrier\\'s own timestamp")
        val dialog = source("ui/conversation/MessageDetailsDialog.kt")
        assertThat(dialog).contains("row.acknowledgedAtMs != null ->")
        assertThat(dialog).contains("preciseTimestampLabel(row.acknowledgedAtMs, is24Hour)")
        assertThat(dialog).contains("R.string.message_details_delivered_at_note")
    }
}
