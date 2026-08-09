package app.clearsms.ui.components

import app.clearsms.domain.model.SwipeAction

/** Horizontal swipe direction on an inbox row (layout-relative). */
enum class SwipeDirection {
    START_TO_END,
    END_TO_START,
}

/**
 * Maps a completed swipe to the user's configured action for that direction.
 * [SwipeAction.NONE] means the direction is disabled - the caller must not
 * perform anything (and should not have allowed the dismissal at all).
 */
fun resolveSwipeAction(
    direction: SwipeDirection,
    startAction: SwipeAction,
    endAction: SwipeAction,
): SwipeAction =
    when (direction) {
        SwipeDirection.START_TO_END -> startAction
        SwipeDirection.END_TO_START -> endAction
    }
