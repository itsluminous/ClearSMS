package app.clearsms.ui.components

import app.clearsms.domain.model.SwipeAction
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class SwipeConfirmationTest {
    @Test
    fun `archive swipe performs immediately`() {
        assertThat(SwipeConfirmation.onSwipe(SwipeAction.ARCHIVE, confirmationPending = false))
            .isEqualTo(SwipeConfirmation.Outcome.Perform(SwipeAction.ARCHIVE))
    }

    @Test
    fun `read toggle swipe performs immediately`() {
        assertThat(SwipeConfirmation.onSwipe(SwipeAction.TOGGLE_READ, confirmationPending = false))
            .isEqualTo(SwipeConfirmation.Outcome.Perform(SwipeAction.TOGGLE_READ))
    }

    @Test
    fun `delete swipe requests confirmation instead of deleting`() {
        assertThat(SwipeConfirmation.onSwipe(SwipeAction.DELETE, confirmationPending = false))
            .isEqualTo(SwipeConfirmation.Outcome.RequestConfirmation)
    }

    @Test
    fun `rapid second delete swipe while confirmation is pending is ignored`() {
        assertThat(SwipeConfirmation.onSwipe(SwipeAction.DELETE, confirmationPending = true))
            .isEqualTo(SwipeConfirmation.Outcome.Ignore)
    }

    @Test
    fun `disabled direction is ignored`() {
        assertThat(SwipeConfirmation.onSwipe(SwipeAction.NONE, confirmationPending = false))
            .isEqualTo(SwipeConfirmation.Outcome.Ignore)
    }

    @Test
    fun `confirm deletes exactly once`() {
        // Pending confirmation: the delete fires and the caller clears the flag.
        var pending = true
        assertThat(SwipeConfirmation.shouldDeleteOnConfirm(pending)).isTrue()
        pending = false
        // A second confirm (double tap / stale callback) must not delete again.
        assertThat(SwipeConfirmation.shouldDeleteOnConfirm(pending)).isFalse()
    }

    @Test
    fun `cancel restores without deleting`() {
        // Cancelling simply clears the pending flag; a following confirm is a no-op.
        val pendingAfterCancel = false
        assertThat(SwipeConfirmation.shouldDeleteOnConfirm(pendingAfterCancel)).isFalse()
        // And a fresh swipe re-requests confirmation from the clean state.
        assertThat(SwipeConfirmation.onSwipe(SwipeAction.DELETE, pendingAfterCancel))
            .isEqualTo(SwipeConfirmation.Outcome.RequestConfirmation)
    }
}
