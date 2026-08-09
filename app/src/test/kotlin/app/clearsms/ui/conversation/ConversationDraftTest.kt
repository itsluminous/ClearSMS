package app.clearsms.ui.conversation

import app.clearsms.testing.FakeMessageRepository
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Test

class ConversationDraftTest {
    private val repository = FakeMessageRepository()

    // A standalone scope on the shared scheduler: backgroundScope does not
    // reliably run collectors under advanceUntilIdle in coroutines 1.9.
    private fun TestScope.draft(threadId: Long = 7L): ConversationDraft =
        ConversationDraft(
            threadId = threadId,
            repository = repository,
            scope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler)),
            ioDispatcher = StandardTestDispatcher(testScheduler),
        )

    @Test
    fun `typing persists the draft so it survives process death`() =
        runTest {
            val draft = draft()
            draft.set("meet at 6")
            advanceUntilIdle()

            assertThat(repository.drafts[7L]).isEqualTo("meet at 6")

            // A fresh holder (new conversation open / new process) restores it.
            val reopened = draft()
            advanceUntilIdle()
            assertThat(reopened.text.value).isEqualTo("meet at 6")
        }

    @Test
    fun `clearing the compose text deletes the saved draft`() =
        runTest {
            val draft = draft()
            draft.set("half a thought")
            advanceUntilIdle()
            draft.set("")
            advanceUntilIdle()

            assertThat(repository.drafts).doesNotContainKey(7L)
        }

    @Test
    fun `consume on send clears the field and the saved draft`() =
        runTest {
            val draft = draft()
            draft.set("sending this")
            advanceUntilIdle()

            draft.consume()
            advanceUntilIdle()

            assertThat(draft.text.value).isEmpty()
            assertThat(repository.drafts).doesNotContainKey(7L)
        }

    @Test
    fun `consume on schedule leaves no leftover draft either`() =
        runTest {
            // Scheduling routes through the same consume() as sending - this
            // pins that contract at the holder level.
            val draft = draft()
            draft.set("later message")
            advanceUntilIdle()

            draft.consume()
            advanceUntilIdle()

            assertThat(repository.drafts).doesNotContainKey(7L)
            assertThat(draft.text.value).isEmpty()
        }

    @Test
    fun `the last of a keystroke burst wins`() =
        runTest {
            val draft = draft()
            draft.set("a")
            draft.set("ab")
            draft.set("abc")
            advanceUntilIdle()

            assertThat(repository.drafts[7L]).isEqualTo("abc")
        }

    @Test
    fun `text typed before the saved draft loads is not clobbered`() =
        runTest {
            repository.drafts[7L] = "old saved draft"
            val draft = draft()
            draft.set("fresh typing")
            advanceUntilIdle()

            assertThat(draft.text.value).isEqualTo("fresh typing")
            assertThat(repository.drafts[7L]).isEqualTo("fresh typing")
        }
}
