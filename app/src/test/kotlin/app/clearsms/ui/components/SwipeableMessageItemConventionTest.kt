package app.clearsms.ui.components

import com.google.common.truth.Truth.assertWithMessage
import org.junit.Test
import java.io.File

/**
 * Source-level convention (the repo pattern for behaviour with no Compose UI
 * test harness): the inbox row's swipe gesture must keep the discipline that
 * fixes issue #16.
 *
 * Material3's SwipeToDismissBox claims a gesture as soon as horizontal
 * movement alone crosses touch slop, so a slightly diagonal scroll flick
 * swipes a row instead of scrolling the list. The row therefore drives its
 * own pointer loop whose only claim/yield authority is the pure, unit-tested
 * [evaluateSwipeClaim]. These assertions fail if someone reverts to
 * SwipeToDismissBox or inlines ad-hoc slop math that would drift from the
 * tested logic.
 */
class SwipeableMessageItemConventionTest {
    private val source = File("src/main/kotlin/app/clearsms/ui/components/SwipeableMessageItem.kt").readText()

    @Test
    fun `the row does not use SwipeToDismissBox for gesture recognition`() {
        assertWithMessage(
            "SwipeableMessageItem must not delegate gesture recognition to SwipeToDismissBox: " +
                "it claims on horizontal slop alone, which turns diagonal scroll flicks into swipes (issue #16)",
        ).that(source)
            .doesNotContain("SwipeToDismissBox(")
    }

    @Test
    fun `gesture claiming goes through the shared axis-dominance verdict`() {
        assertWithMessage("the pointer loop must consult the pure, unit-tested claim logic")
            .that(source)
            .contains("evaluateSwipeClaim(")
        assertWithMessage("a YIELD verdict must abandon the gesture so the list scroll keeps it")
            .that(source)
            .contains("SwipeClaimVerdict.YIELD -> return@awaitEachGesture")
        assertWithMessage("a gesture already consumed elsewhere (an active scroll) must never be claimed")
            .that(source)
            .contains("if (change.isConsumed) return@awaitEachGesture")
    }

    @Test
    fun `trigger thresholds stay at the Material3 values this component replaced`() {
        // The rewrite fixes gesture RECOGNITION; how far a recognised swipe
        // must travel is deliberately unchanged. Changing these values is a
        // product decision that must be taken (and reported) explicitly.
        assertWithMessage("positional threshold must stay at SwipeToDismissBox's 56.dp")
            .that(source)
            .contains("SwipePositionalThreshold = 56.dp")
        assertWithMessage("velocity threshold must stay at SwipeToDismissBox's 125.dp/s")
            .that(source)
            .contains("SwipeVelocityThreshold = 125.dp")
    }
}
