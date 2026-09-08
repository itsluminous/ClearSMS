package app.clearsms.ui.components

import kotlin.math.abs

/**
 * Verdict for an in-progress touch on a swipeable inbox row (issue #16).
 *
 * Material's SwipeToDismissBox claims a gesture as soon as HORIZONTAL
 * movement alone crosses touch slop - and because the row's drag detector
 * runs before the list's scroll detector, a fast, slightly diagonal scroll
 * flick can cross horizontal slop first and turn into a swipe. The fix is
 * axis dominance: the row may only claim once horizontal movement both
 * crosses slop AND strictly exceeds vertical movement, and it must yield as
 * soon as vertical movement crosses slop without being dominated. Ties go
 * to the scroll, because a stolen scroll is far more annoying than a swipe
 * that needs a slightly flatter finger.
 */
enum class SwipeClaimVerdict {
    /** Horizontal movement dominates past slop: the row owns the gesture. */
    CLAIM,

    /** Vertical movement crossed slop undominated: the scroll owns it. */
    YIELD,

    /** Not enough movement to decide yet. */
    UNDECIDED,
}

/**
 * Decides whether accumulated movement ([totalDx], [totalDy], both in px
 * from the touch down) is a horizontal swipe, a vertical scroll, or still
 * ambiguous. [touchSlop] is the platform's movement threshold in px.
 *
 * Pure and total: exercised directly by unit tests, and by the gesture loop
 * in SwipeableMessageItem on every pointer event until it stops returning
 * [SwipeClaimVerdict.UNDECIDED].
 */
fun evaluateSwipeClaim(
    totalDx: Float,
    totalDy: Float,
    touchSlop: Float,
): SwipeClaimVerdict {
    val horizontal = abs(totalDx)
    val vertical = abs(totalDy)
    return when {
        // Checked first so a movement that crosses slop on both axes at once
        // (a fast diagonal) resolves as a scroll unless horizontal dominates.
        vertical > touchSlop && vertical >= horizontal -> SwipeClaimVerdict.YIELD
        horizontal > touchSlop && horizontal > vertical -> SwipeClaimVerdict.CLAIM
        else -> SwipeClaimVerdict.UNDECIDED
    }
}
