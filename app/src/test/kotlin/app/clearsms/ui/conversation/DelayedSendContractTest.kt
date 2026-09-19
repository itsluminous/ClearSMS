package app.clearsms.ui.conversation

import app.clearsms.data.backup.SettingsBackupCatalog
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import org.junit.Test
import java.io.File

/**
 * Source-level contract for delayed sending (GitHub #40) - the pieces a
 * refactor could silently drop without any behavioural test noticing:
 * the feature stays OFF by default (the maintainer's decision on the
 * issue), the pending snackbar's Cancel action is actually wired (a dead
 * undo action would be data loss), the bar lives exactly as long as the
 * delay, and both preference keys are registered for settings backup.
 */
class DelayedSendContractTest {
    private fun source(path: String): String {
        val file = File("src/main/kotlin/app/clearsms/$path")
        assertWithMessage("missing source file $path").that(file.exists()).isTrue()
        return file.readText()
    }

    @Test
    fun `delayed sending defaults OFF - the maintainer's decision on the issue`() {
        val impl = source("data/prefs/SettingsRepositoryImpl.kt")
        assertThat(impl).contains("it[KEY_DELAYED_SEND_ENABLED] ?: false")
    }

    @Test
    fun `the pending snackbar's Cancel action is wired to cancelDelayedSend - never a dead button`() {
        val screen = source("ui/conversation/ConversationScreen.kt")
        val delayedBlock = screen.substringAfter("is SendEvent.Delayed ->")
        assertThat(delayedBlock).contains("SnackbarResult.ActionPerformed")
        assertThat(delayedBlock).contains("viewModel.cancelDelayedSend(event.messageId)")
    }

    @Test
    fun `the pending bar lives exactly as long as the delay - Indefinite duration torn down by the timeout`() {
        val screen = source("ui/conversation/ConversationScreen.kt")
        val delayedBlock = screen.substringAfter("is SendEvent.Delayed ->")
        assertThat(delayedBlock).contains("withTimeoutOrNull(event.delaySeconds * 1000L)")
        assertThat(delayedBlock).contains("SnackbarDuration.Indefinite")
    }

    @Test
    fun `both preference keys are registered for settings backup`() {
        val backedUp = SettingsBackupCatalog.byName.keys
        assertThat(backedUp).contains("delayed_send_enabled")
        assertThat(backedUp).contains("delayed_send_delay")
        // And neither is (contradictorily) excluded.
        assertThat(SettingsBackupCatalog.excludedKeys).containsNoneOf("delayed_send_enabled", "delayed_send_delay")
    }

    @Test
    fun `cancelling restores the ORIGINAL typed text, not the accent-folded wire body`() {
        val vm = source("ui/conversation/ConversationViewModel.kt")
        assertThat(vm).contains("pendingDelayedOriginals[messageId] = body")
        assertThat(vm).contains("conversationDraft.restore(original ?: persistedBody)")
    }
}
