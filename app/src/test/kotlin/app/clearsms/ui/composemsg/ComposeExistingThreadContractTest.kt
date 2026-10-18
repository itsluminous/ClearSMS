package app.clearsms.ui.composemsg

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import org.junit.Test
import java.io.File

/**
 * Source-level contract for routing a composer recipient to their existing
 * conversation (issue #119) - the decisions a refactor could silently
 * undo without a behavioural test noticing:
 * - the thread is found through the app's own identity ([ThreadIdentity]:
 *   platform anchor first, sender key second - the #42 rule every insert
 *   uses), never through a bare number lookup;
 * - a PICK pushes the conversation over the composer (Back recovers a
 *   mis-tap with the text intact) while an opened-with recipient replaces
 *   it; both go through the plain conversation route a notification tap
 *   uses, never a hand-rolled nav option that could corrupt a tab's saved
 *   stack;
 * - typing never routes - only a pick or an opened-with recipient does.
 */
class ComposeExistingThreadContractTest {
    private fun source(path: String): String {
        val file = File("src/main/kotlin/app/clearsms/$path")
        assertWithMessage("missing source file $path").that(file.exists()).isTrue()
        return file.readText()
    }

    @Test
    fun `the recipient's thread is resolved through ThreadIdentity - the platform-anchored #42 identity, never a raw number match`() {
        val resolver = source("sms/ExistingThreadResolver.kt")
        assertThat(resolver).contains("providerThreads.threadIdFor(trimmed)")
        assertThat(resolver).contains(".existing(messageDao, trimmed, normalized, providerThreadId)")
        assertThat(resolver).doesNotContain("threadIdFor(normalized)")
        // ThreadIdentity.resolve (inserts) and .existing (lookups) are one
        // decision: resolve is defined on top of existing.
        val identity = source("data/repository/ThreadIdentity.kt")
        assertThat(identity).contains("existing(dao, sender, normalized, providerThreadId, recipientCount)\n            ?: ((dao.maxThreadId() ?: 0L) + 1L)")
        val composer = source("ui/composemsg/ComposeMessageViewModel.kt")
        assertThat(composer).contains("existingThreads.threadFor(address)")
        assertThat(composer).doesNotContain("messageDao.threadIdFor(")
    }

    @Test
    fun `a pick pushes the conversation, an opened-with recipient replaces the composer, typing does neither`() {
        val composer = source("ui/composemsg/ComposeMessageViewModel.kt")
        val pick = composer.substringAfter("fun pickSuggestion(").substringBefore("fun clearPicked(")
        assertThat(pick).contains("routeToExistingThread(state.value.recipient, replaceComposer = false)")
        val typing = composer.substringAfter("fun onRecipientChange(").substringBefore("fun pickSuggestion(")
        assertThat(typing).doesNotContain("routeToExistingThread")
        val init = composer.substringAfter("init {").substringBefore("private fun routeToExistingThread(")
        assertThat(init).contains("routeToExistingThread(address, replaceComposer = true)")
        // The hand-off never names a recipient the user has since changed.
        assertThat(composer).contains("if (state.value.recipient.trim() != address.trim()) return")
    }

    @Test
    fun `both routes use the plain conversation route a notification tap uses`() {
        val app = source("ui/navigation/ClearSmsApp.kt")
        val composeBlock = app.substringAfter("route = Routes.COMPOSE,").substringBefore("route = Routes.ACCOUNT_DETAIL,")
        assertThat(composeBlock).contains("onShowConversation = { threadId -> navController.navigate(Routes.conversation(threadId)) }")
        assertThat(composeBlock).contains("popUpTo(Routes.COMPOSE) { inclusive = true }")
        val screen = source("ui/composemsg/ComposeMessageScreen.kt")
        assertThat(screen).contains("viewModel.showThreadFlow.collect { threadId -> onShowConversation(threadId) }")
    }

    @Test
    fun `the carried text is a transient seed - shown, never saved as the wrong thread's draft`() {
        val draft = source("ui/conversation/ConversationDraft.kt")
        assertThat(draft).contains("draftText.collectLatest { if (it != transient) repository.saveDraft(threadId, it) }")
        val vm = source("ui/conversation/ConversationViewModel.kt")
        assertThat(vm).contains("seed = handOff?.draft")
    }
}
