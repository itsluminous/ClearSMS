package app.clearsms.domain.model

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Pins the dead zone's pure geometry (issue #16): touch containment, bounds
 * clamping, the off state, the guarantee that a maximal zone still leaves
 * part of every row swipeable, the storage round trip, and - critically -
 * that [SwipeDeadZone.blocksTouchAt] agrees exactly with the rectangle
 * [SwipeDeadZone.bounds] returns, because the settings preview draws that
 * rectangle while the gesture consults blocksTouchAt.
 */
class SwipeDeadZoneTest {
    @Test
    fun `disabled zone blocks nothing and has no bounds`() {
        val zone = SwipeDeadZone.DEFAULT
        assertThat(zone.enabled).isFalse()
        assertThat(zone.bounds()).isNull()
        // The off state is exactly today's behaviour: no coordinate blocks.
        for (x in 0..10) {
            for (y in 0..10) {
                assertThat(zone.blocksTouchAt(x / 10f, y / 10f)).isFalse()
            }
        }
    }

    @Test
    fun `a centred default-tuning zone blocks the middle and frees the edges`() {
        val zone = SwipeDeadZone.DEFAULT.copy(enabled = true)
        // Centre 50%, width 40%, height 100%: band spans x in 0.3..0.7.
        assertThat(zone.blocksTouchAt(0.5f, 0.5f)).isTrue()
        assertThat(zone.blocksTouchAt(0.31f, 0.05f)).isTrue()
        assertThat(zone.blocksTouchAt(0.69f, 0.95f)).isTrue()
        assertThat(zone.blocksTouchAt(0.29f, 0.5f)).isFalse()
        assertThat(zone.blocksTouchAt(0.71f, 0.5f)).isFalse()
        assertThat(zone.blocksTouchAt(0.05f, 0.5f)).isFalse()
        assertThat(zone.blocksTouchAt(0.95f, 0.5f)).isFalse()
    }

    @Test
    fun `height below 100 percent frees strips at the row top and bottom`() {
        val zone = SwipeDeadZone(enabled = true, centerXPercent = 50, widthPercent = 40, heightPercent = 50)
        // Band spans y in 0.25..0.75.
        assertThat(zone.blocksTouchAt(0.5f, 0.5f)).isTrue()
        assertThat(zone.blocksTouchAt(0.5f, 0.2f)).isFalse()
        assertThat(zone.blocksTouchAt(0.5f, 0.8f)).isFalse()
    }

    @Test
    fun `the zone is clamped to stay inside the row when positioned at an edge`() {
        val zone = SwipeDeadZone(enabled = true, centerXPercent = 0, widthPercent = 40, heightPercent = 100)
        val bounds = requireNotNull(zone.bounds())
        assertThat(bounds.left).isEqualTo(0f)
        assertThat(bounds.right).isEqualTo(0.4f)

        val right = SwipeDeadZone(enabled = true, centerXPercent = 100, widthPercent = 40, heightPercent = 100)
        val rightBounds = requireNotNull(right.bounds())
        assertThat(rightBounds.left).isEqualTo(0.6f)
        assertThat(rightBounds.right).isEqualTo(1f)
    }

    @Test
    fun `sanitized coerces every value into its legal range`() {
        val wild = SwipeDeadZone(enabled = true, centerXPercent = 400, widthPercent = 100, heightPercent = -5)
        val clean = wild.sanitized()
        assertThat(clean.centerXPercent).isEqualTo(SwipeDeadZone.MAX_CENTER)
        assertThat(clean.widthPercent).isEqualTo(SwipeDeadZone.MAX_WIDTH)
        assertThat(clean.heightPercent).isEqualTo(SwipeDeadZone.MIN_HEIGHT)
    }

    @Test
    fun `even the maximal zone leaves at least a fifth of every row swipeable`() {
        // MAX_WIDTH is the bound that stops the dead zone from silently
        // disabling swipes: no position can cover the whole row width.
        for (center in SwipeDeadZone.MIN_CENTER..SwipeDeadZone.MAX_CENTER step 5) {
            val zone =
                SwipeDeadZone(
                    enabled = true,
                    centerXPercent = center,
                    widthPercent = SwipeDeadZone.MAX_WIDTH,
                    heightPercent = SwipeDeadZone.MAX_HEIGHT,
                )
            val bounds = requireNotNull(zone.bounds())
            val covered = bounds.right - bounds.left
            assertThat(covered).isLessThan(0.801f)
            // The free 20% is contiguous on one side or split across both,
            // but always present (tolerance for float rounding).
            val free = bounds.left + (1f - bounds.right)
            assertThat(free).isGreaterThan(0.199f)
        }
    }

    @Test
    fun `blocksTouchAt agrees exactly with the bounds rectangle the preview draws`() {
        // The settings preview shades bounds(); the gesture asks
        // blocksTouchAt(). This agreement is what makes the preview honest.
        val samples =
            listOf(
                SwipeDeadZone.DEFAULT,
                SwipeDeadZone.DEFAULT.copy(enabled = true),
                SwipeDeadZone(enabled = true, centerXPercent = 20, widthPercent = 60, heightPercent = 50),
                SwipeDeadZone(enabled = true, centerXPercent = 90, widthPercent = 10, heightPercent = 25),
                SwipeDeadZone(enabled = true, centerXPercent = 0, widthPercent = 80, heightPercent = 100),
            )
        samples.forEach { zone ->
            val bounds = zone.bounds()
            for (xi in 0..20) {
                for (yi in 0..20) {
                    val x = xi / 20f
                    val y = yi / 20f
                    val inRect =
                        bounds != null &&
                            x >= bounds.left &&
                            x <= bounds.right &&
                            y >= bounds.top &&
                            y <= bounds.bottom
                    assertThat(zone.blocksTouchAt(x, y)).isEqualTo(inRect)
                }
            }
        }
    }

    @Test
    fun `encode and decode round trip`() {
        val zone = SwipeDeadZone(enabled = true, centerXPercent = 35, widthPercent = 55, heightPercent = 70)
        assertThat(SwipeDeadZone.decode(zone.encode())).isEqualTo(zone)
        val off = SwipeDeadZone(enabled = false, centerXPercent = 60, widthPercent = 25, heightPercent = 100)
        // Tuning survives the off state, so re-enabling restores it.
        assertThat(SwipeDeadZone.decode(off.encode())).isEqualTo(off)
    }

    @Test
    fun `decode is lenient about garbage`() {
        listOf(
            null,
            "",
            "v1",
            "v2:1:50:40:100",
            "v1:maybe:50:40:100",
            "v1:1:fifty:40:100",
            "v1:1:50:40",
            "v1:1:50:40:100:extra",
        ).forEach { stored ->
            assertThat(SwipeDeadZone.decode(stored)).isEqualTo(SwipeDeadZone.DEFAULT)
        }
        // Out-of-range stored values are clamped, not rejected.
        assertThat(SwipeDeadZone.decode("v1:1:200:99:5"))
            .isEqualTo(
                SwipeDeadZone(
                    enabled = true,
                    centerXPercent = SwipeDeadZone.MAX_CENTER,
                    widthPercent = SwipeDeadZone.MAX_WIDTH,
                    heightPercent = SwipeDeadZone.MIN_HEIGHT,
                ),
            )
    }
}
