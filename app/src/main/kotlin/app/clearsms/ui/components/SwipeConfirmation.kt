package app.clearsms.ui.components

import app.clearsms.domain.model.SwipeAction

/**
 * Pure decision logic for swipe-triggered actions.
 *
 * Archive and read-toggle are reversible and run immediately. Delete also
 * removes the message from the system SMS provider — irreversible — so it
 * must be confirmed first. The state machine guarantees the delete fires at
 * most once per confirmation, even when the row is swiped repeatedly while
 * the dialog is up.
 */
object SwipeConfirmation {
    /** What the UI should do after a completed swipe. */
    sealed interface Outcome {
        /** Run [action] immediately (reversible action). */
        data class Perform(
            val action: SwipeAction,
        ) : Outcome

        /** Show the delete confirmation; nothing is deleted yet. */
        data object RequestConfirmation : Outcome

        /** Do nothing (disabled direction, or a confirmation is already pending). */
        data object Ignore : Outcome
    }

    /** Decides the outcome of a completed swipe given the pending-confirmation flag. */
    fun onSwipe(
        action: SwipeAction,
        confirmationPending: Boolean,
    ): Outcome =
        when {
            action == SwipeAction.NONE -> Outcome.Ignore
            action == SwipeAction.DELETE ->
                if (confirmationPending) Outcome.Ignore else Outcome.RequestConfirmation
            else -> Outcome.Perform(action)
        }

    /**
     * Whether a confirmation may fire the delete. Returns true only when a
     * confirmation was actually pending — the caller must clear the flag
     * alongside, making the delete fire exactly once.
     */
    fun shouldDeleteOnConfirm(confirmationPending: Boolean): Boolean = confirmationPending
}
