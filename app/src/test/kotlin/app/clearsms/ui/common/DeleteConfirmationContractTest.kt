package app.clearsms.ui.common

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import org.junit.Test
import java.io.File

/**
 * Source-level contract (the repo has no Compose UI harness, same style as
 * `UnreadToggleContractTest`) that the delete dialogs cannot drift back to
 * lying about the recycle bin:
 *
 *  - the inbox, archived and conversation screens never hardcode a delete
 *    body string - they ask [DeleteConfirmationText] with the bin flag from
 *    their own UiState, which each ViewModel fills from the settings flow
 *    (never a repository read from a composable);
 *  - the bin's own Delete forever / Empty bin dialogs keep their permanent
 *    wording, because those really are permanent;
 *  - the UNDO snackbar path is untouched.
 */
class DeleteConfirmationContractTest {
    private fun source(path: String) = File("src/main/kotlin/app/clearsms/$path").readText()

    private val screens =
        listOf(
            "ui/inbox/InboxScreen.kt",
            "ui/inbox/ArchivedScreen.kt",
            "ui/conversation/ConversationScreen.kt",
        )

    /** The four delete bodies; a screen naming any of them directly bypasses the decision. */
    private val deleteBodies =
        listOf(
            "R.string.selection_delete_threads_message",
            "R.string.selection_delete_threads_to_bin_message",
            "R.string.selection_delete_messages_message",
            "R.string.selection_delete_messages_to_bin_message",
        )

    @Test
    fun `no delete screen hardcodes a delete body - each asks DeleteConfirmationText`() {
        for (path in screens) {
            val src = source(path)
            for (body in deleteBodies) {
                assertWithMessage("$path must not reference $body directly").that(src).doesNotContain(body)
            }
            assertWithMessage("$path must decide its delete wording through DeleteConfirmationText")
                .that(src)
                .contains("DeleteConfirmationText.bodyRes(")
            // The flag comes from the screen's UiState, not a repository.
            assertThat(src).contains("state.recycleBinEnabled")
            assertThat(src).doesNotContain("SettingsRepository")
            assertThat(src).doesNotContain("recycleBinEnabled.first()")
        }
    }

    @Test
    fun `each screen picks the target that matches what its ViewModel deletes`() {
        // Inbox and Archived stage whole threads (stageDeleteThreads);
        // Conversation stages individual messages (stageDeleteMessages).
        assertThat(source("ui/inbox/InboxScreen.kt")).contains("DeleteConfirmationText.Target.CONVERSATIONS")
        assertThat(source("ui/inbox/ArchivedScreen.kt")).contains("DeleteConfirmationText.Target.CONVERSATIONS")
        assertThat(source("ui/conversation/ConversationScreen.kt")).contains("DeleteConfirmationText.Target.MESSAGES")
        assertThat(source("ui/inbox/InboxViewModel.kt")).contains("undoManager.stageDeleteThreads(ids)")
        assertThat(source("ui/inbox/ArchivedViewModel.kt")).contains("undoManager.stageDeleteThreads(ids)")
        assertThat(source("ui/conversation/ConversationViewModel.kt")).contains("undoManager.stageDeleteMessages(ids)")
    }

    @Test
    fun `every ViewModel feeds the bin setting into its UiState from the settings flow`() {
        for (vm in listOf("ui/inbox/InboxViewModel.kt", "ui/inbox/ArchivedViewModel.kt", "ui/conversation/ConversationViewModel.kt")) {
            val src = source(vm)
            assertWithMessage("$vm must observe settings.recycleBinEnabled").that(src).contains("settings.recycleBinEnabled")
            assertWithMessage(
                "$vm must expose recycleBinEnabled on its UiState",
            ).that(src).contains("val recycleBinEnabled: Boolean = true")
        }
    }

    @Test
    fun `the bin's own dialogs keep the permanent wording`() {
        val bin = source("ui/inbox/BinScreen.kt")
        assertThat(bin).contains("R.string.bin_delete_forever_message")
        assertThat(bin).contains("R.string.bin_empty_confirm_message")
        assertThat(bin).doesNotContain("DeleteConfirmationText")
        val strings = File("src/main/res/values/strings_bin.xml").readText()
        assertThat(strings).contains(
            "<string name=\"bin_delete_forever_message\">This message will be permanently removed. This cannot be undone.</string>",
        )
        assertThat(strings).contains(
            "<string name=\"bin_empty_confirm_message\">All %1\$d messages in the bin will be permanently removed. This cannot be undone.</string>",
        )
    }

    @Test
    fun `undo snackbar path is unchanged`() {
        for (path in screens) {
            assertThat(source(path)).contains("UndoUiEvent.Deleted")
        }
        val undo = source("data/repository/UndoManager.kt")
        assertThat(undo).contains("repository.commitStagedDelete(staged, toBin = recycleBinEnabled())")
        assertThat(undo).contains("const val UNDO_WINDOW_MS = 5_000L")
    }
}
