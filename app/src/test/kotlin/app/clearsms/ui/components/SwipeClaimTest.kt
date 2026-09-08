package app.clearsms.ui.components

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Pins the axis-dominance discipline that keeps a vertical scroll from
 * turning into a horizontal swipe (issue #16): the row claims the gesture
 * only when horizontal movement crosses touch slop AND strictly dominates
 * vertical movement; it yields as soon as vertical movement crosses slop
 * undominated; ties go to the scroll.
 */
class SwipeClaimTest {
    private val slop = 24f

    @Test
    fun `dominant horizontal movement past slop claims the gesture`() {
        assertThat(evaluateSwipeClaim(totalDx = 30f, totalDy = 4f, touchSlop = slop))
            .isEqualTo(SwipeClaimVerdict.CLAIM)
        // Direction does not matter, magnitude does.
        assertThat(evaluateSwipeClaim(totalDx = -30f, totalDy = -4f, touchSlop = slop))
            .isEqualTo(SwipeClaimVerdict.CLAIM)
    }

    @Test
    fun `a scroll-like gesture yields even when its horizontal component crosses slop`() {
        // The reporter's case: a fast, slightly diagonal scroll flick whose
        // horizontal component alone would satisfy SwipeToDismissBox.
        assertThat(evaluateSwipeClaim(totalDx = 30f, totalDy = 60f, touchSlop = slop))
            .isEqualTo(SwipeClaimVerdict.YIELD)
        assertThat(evaluateSwipeClaim(totalDx = -26f, totalDy = 80f, touchSlop = slop))
            .isEqualTo(SwipeClaimVerdict.YIELD)
    }

    @Test
    fun `vertical movement past slop yields`() {
        assertThat(evaluateSwipeClaim(totalDx = 0f, totalDy = 25f, touchSlop = slop))
            .isEqualTo(SwipeClaimVerdict.YIELD)
        assertThat(evaluateSwipeClaim(totalDx = 2f, totalDy = -40f, touchSlop = slop))
            .isEqualTo(SwipeClaimVerdict.YIELD)
    }

    @Test
    fun `a perfect diagonal past slop goes to the scroll, not the swipe`() {
        assertThat(evaluateSwipeClaim(totalDx = 40f, totalDy = 40f, touchSlop = slop))
            .isEqualTo(SwipeClaimVerdict.YIELD)
        assertThat(evaluateSwipeClaim(totalDx = -40f, totalDy = 40f, touchSlop = slop))
            .isEqualTo(SwipeClaimVerdict.YIELD)
    }

    @Test
    fun `movement under slop stays undecided`() {
        assertThat(evaluateSwipeClaim(totalDx = 0f, totalDy = 0f, touchSlop = slop))
            .isEqualTo(SwipeClaimVerdict.UNDECIDED)
        assertThat(evaluateSwipeClaim(totalDx = 23f, totalDy = 2f, touchSlop = slop))
            .isEqualTo(SwipeClaimVerdict.UNDECIDED)
        assertThat(evaluateSwipeClaim(totalDx = 10f, totalDy = 23f, touchSlop = slop))
            .isEqualTo(SwipeClaimVerdict.UNDECIDED)
        // Exactly at slop is not past it, on either axis.
        assertThat(evaluateSwipeClaim(totalDx = slop, totalDy = 0f, touchSlop = slop))
            .isEqualTo(SwipeClaimVerdict.UNDECIDED)
        assertThat(evaluateSwipeClaim(totalDx = 0f, totalDy = slop, touchSlop = slop))
            .isEqualTo(SwipeClaimVerdict.UNDECIDED)
    }

    @Test
    fun `horizontal movement just past slop claims only when it dominates`() {
        assertThat(evaluateSwipeClaim(totalDx = 25f, totalDy = 24f, touchSlop = slop))
            .isEqualTo(SwipeClaimVerdict.CLAIM)
        // Vertical equals horizontal: the scroll keeps it.
        assertThat(evaluateSwipeClaim(totalDx = 25f, totalDy = 25f, touchSlop = slop))
            .isEqualTo(SwipeClaimVerdict.YIELD)
    }
}
