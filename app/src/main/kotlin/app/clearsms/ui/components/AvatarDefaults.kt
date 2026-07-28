package app.clearsms.ui.components

import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * The single source of truth for avatar geometry.
 *
 * Every avatar in the app — contact photos, bundled logos, generated brand
 * tiles, category-glyph tiles and letter avatars — clips to [shape] at one
 * of exactly two sanctioned diameters: [size] for list rows and headers,
 * and [compactSize] for dense card corners (the Alerts reminder cards),
 * where a full-size avatar overwhelms the label text next to it. CIRCULAR
 * was chosen because contact photos are conventionally circular in Android
 * messaging UIs, and the bundled bank/brand marks are designed on circular
 * or square fields that crop cleanly into a circle.
 *
 * Call sites must not invent other shapes or diameters: [shapeFor] and
 * [sizeFor] deliberately resolve every [AvatarStyle] to the same values so a
 * unit test can assert the invariant.
 */
object AvatarDefaults {
    /** The one avatar shape used everywhere. */
    val shape: Shape = CircleShape

    /** The standard avatar diameter (inbox rows, conversation, search, finance). */
    val size: Dp = 40.dp

    /**
     * The compact diameter for avatars sitting inline with label-size card
     * text (Alerts reminder cards) — same circle, smaller visual weight.
     */
    val compactSize: Dp = 28.dp

    /** Every avatar variant renders with the same [shape]. */
    fun shapeFor(
        @Suppress("UNUSED_PARAMETER") style: AvatarStyle,
    ): Shape = shape

    /** Every avatar variant renders with the same [size]. */
    fun sizeFor(
        @Suppress("UNUSED_PARAMETER") style: AvatarStyle,
    ): Dp = size
}
