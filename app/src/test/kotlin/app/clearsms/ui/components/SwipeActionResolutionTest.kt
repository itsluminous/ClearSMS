package app.clearsms.ui.components

import app.clearsms.domain.model.SwipeAction
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class SwipeActionResolutionTest {
    @Test
    fun `start-to-end swipe performs the configured start action`() {
        assertThat(resolveSwipeAction(SwipeDirection.START_TO_END, SwipeAction.ARCHIVE, SwipeAction.DELETE))
            .isEqualTo(SwipeAction.ARCHIVE)
        assertThat(resolveSwipeAction(SwipeDirection.START_TO_END, SwipeAction.TOGGLE_READ, SwipeAction.DELETE))
            .isEqualTo(SwipeAction.TOGGLE_READ)
    }

    @Test
    fun `end-to-start swipe performs the configured end action`() {
        assertThat(resolveSwipeAction(SwipeDirection.END_TO_START, SwipeAction.ARCHIVE, SwipeAction.DELETE))
            .isEqualTo(SwipeAction.DELETE)
        assertThat(resolveSwipeAction(SwipeDirection.END_TO_START, SwipeAction.ARCHIVE, SwipeAction.TOGGLE_READ))
            .isEqualTo(SwipeAction.TOGGLE_READ)
    }

    @Test
    fun `a NONE direction resolves to NONE so nothing is performed`() {
        assertThat(resolveSwipeAction(SwipeDirection.START_TO_END, SwipeAction.NONE, SwipeAction.DELETE))
            .isEqualTo(SwipeAction.NONE)
        assertThat(resolveSwipeAction(SwipeDirection.END_TO_START, SwipeAction.ARCHIVE, SwipeAction.NONE))
            .isEqualTo(SwipeAction.NONE)
    }
}
