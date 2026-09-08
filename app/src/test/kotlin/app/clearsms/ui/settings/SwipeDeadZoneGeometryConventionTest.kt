package app.clearsms.ui.settings

import com.google.common.truth.Truth.assertWithMessage
import org.junit.Test
import java.io.File

/**
 * Source-level convention: the swipe gesture and the settings preview must
 * derive the dead zone from ONE shared geometry source -
 * [app.clearsms.domain.model.SwipeDeadZone.bounds] - so the translucent
 * overlay the user tunes in Settings is exactly the area where swipes will
 * not start. A preview that re-derived the rectangle from the raw percent
 * fields could silently drift from the gesture; these assertions make that
 * drift a test failure instead.
 */
class SwipeDeadZoneGeometryConventionTest {
    private val srcRoot = File("src/main/kotlin/app/clearsms")

    private val modelSource = File(srcRoot, "domain/model/SwipeDeadZone.kt").readText()
    private val gestureSource = File(srcRoot, "ui/components/SwipeableMessageItem.kt").readText()
    private val previewSource = File(srcRoot, "ui/settings/SwipeDeadZoneDialog.kt").readText()

    @Test
    fun `the gesture consults the shared gate and never re-derives geometry`() {
        assertWithMessage("the pointer loop must ask SwipeDeadZone.blocksTouchAt")
            .that(gestureSource)
            .contains("deadZone.blocksTouchAt(")
        assertWithMessage(
            "the gesture must not rebuild the zone from raw percent fields - " +
                "geometry lives only in SwipeDeadZone.bounds()",
        ).that(gestureSource)
            .doesNotContain("Percent")
    }

    @Test
    fun `the preview overlay is drawn from the same bounds the gesture checks`() {
        // Scope to the drawing composable: the dialog's sliders legitimately
        // read the percent fields for EDITING, but DRAWING must use bounds().
        val preview = previewSource.substringAfter("private fun DeadZonePreview").substringBefore("private fun MockInboxRow")
        assertWithMessage("the preview must draw the rectangle from SwipeDeadZone.bounds()")
            .that(preview)
            .contains("zone.bounds()")
        assertWithMessage(
            "the preview must not re-derive the rectangle from raw percent fields - " +
                "that is how a decorative overlay drifts from the real gesture",
        ).that(preview)
            .doesNotContain("Percent")
    }

    @Test
    fun `blocksTouchAt itself is implemented on top of bounds`() {
        val gate = modelSource.substringAfter("fun blocksTouchAt").substringBefore("fun encode")
        assertWithMessage("SwipeDeadZone.blocksTouchAt must delegate to bounds(), the single geometry source")
            .that(gate)
            .contains("bounds() ?: return false")
    }

    @Test
    fun `preview and sliders stay accessible`() {
        assertWithMessage("the preview overlay needs a content description")
            .that(previewSource)
            .contains("settings_swipe_dead_zone_preview_description")
        assertWithMessage("sliders need semantics so the zone is configurable without fine motor control")
            .that(previewSource)
            .contains("contentDescription = label")
    }
}
