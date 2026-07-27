package app.clearsms.ui.components

import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * The single source of truth for avatar geometry.
 *
 * Every avatar in the app — contact photos, bundled logos, generated brand
 * tiles, category-glyph tiles and letter avatars — clips to [shape] at
 * [size]. CIRCULAR was chosen because contact photos are conventionally
 * circular in Android messaging UIs, and the bundled bank/brand marks are
 * designed on circular or square fields that crop cleanly into a circle.
 *
 * Call sites must not override the shape or diameter: [shapeFor] and
 * [sizeFor] deliberately resolve every [AvatarStyle] to the same values so a
 * unit test can assert the invariant.
 */
object AvatarDefaults {
    /** The one avatar shape used everywhere. */
    val shape: Shape = CircleShape

    /** The one avatar diameter used everywhere. */
    val size: Dp = 40.dp

    /** Every avatar variant renders with the same [shape]. */
    fun shapeFor(
        @Suppress("UNUSED_PARAMETER") style: AvatarStyle,
    ): Shape = shape

    /** Every avatar variant renders with the same [size]. */
    fun sizeFor(
        @Suppress("UNUSED_PARAMETER") style: AvatarStyle,
    ): Dp = size
}
