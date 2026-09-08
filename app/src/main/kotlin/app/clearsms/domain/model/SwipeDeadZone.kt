package app.clearsms.domain.model

/**
 * A user-configurable "dead zone" on every inbox row where a horizontal
 * swipe never starts (issue #16's suggested remedy, as extra insurance on
 * top of the axis-dominance fix in the gesture itself).
 *
 * Interpretation: the zone is a BAND ON EACH ROW, not a region of the
 * screen - the reporter asked for "a dead zone in the center of every
 * message item", and a per-row band keeps the protected area under the
 * thumb no matter where in the list the row currently sits. All values are
 * PERCENTAGES of the row's own width/height rather than dp, so the zone
 * covers the same share of every row on every device, font scale and
 * orientation.
 *
 * - [centerXPercent]: horizontal centre of the band, as % of row width
 *   (50 = centred). The band is clamped to stay fully inside the row.
 * - [widthPercent]: band width as % of row width. Capped at [MAX_WIDTH]
 *   (80%), which guarantees at least 20% of every row stays swipeable -
 *   the zone can never silently disable swiping.
 * - [heightPercent]: band height as % of row height, vertically centred.
 *   Below 100%, thin strips at the row's top and bottom stay swipeable.
 *
 * [bounds] is THE single geometry source: the swipe gesture asks
 * [blocksTouchAt] (implemented on top of [bounds]) and the settings preview
 * draws the rectangle [bounds] returns, so the overlay the user sees while
 * tuning is exactly the area the gesture ignores - they cannot drift.
 */
data class SwipeDeadZone(
    val enabled: Boolean,
    val centerXPercent: Int,
    val widthPercent: Int,
    val heightPercent: Int,
) {
    /** The zone with every value coerced into its legal range. */
    fun sanitized(): SwipeDeadZone =
        SwipeDeadZone(
            enabled = enabled,
            centerXPercent = centerXPercent.coerceIn(MIN_CENTER, MAX_CENTER),
            widthPercent = widthPercent.coerceIn(MIN_WIDTH, MAX_WIDTH),
            heightPercent = heightPercent.coerceIn(MIN_HEIGHT, MAX_HEIGHT),
        )

    /** The zone as fractions (0..1) of a row's width and height. */
    data class Bounds(
        val left: Float,
        val top: Float,
        val right: Float,
        val bottom: Float,
    )

    /**
     * The zone's rectangle in fractional row coordinates, or null when the
     * feature is off. Single source of truth for both the gesture gate and
     * the settings preview overlay.
     */
    fun bounds(): Bounds? {
        if (!enabled) return null
        val clean = sanitized()
        val halfWidth = clean.widthPercent / 200f
        val centerX = (clean.centerXPercent / 100f).coerceIn(halfWidth, 1f - halfWidth)
        val halfHeight = clean.heightPercent / 200f
        return Bounds(
            left = centerX - halfWidth,
            top = 0.5f - halfHeight,
            right = centerX + halfWidth,
            bottom = 0.5f + halfHeight,
        )
    }

    /**
     * Whether a touch at fractional row coordinates (x/rowWidth,
     * y/rowHeight) falls inside the dead zone. Always false when disabled,
     * so the off state is byte-for-byte today's behaviour.
     */
    fun blocksTouchAt(
        xFraction: Float,
        yFraction: Float,
    ): Boolean {
        val zone = bounds() ?: return false
        return xFraction >= zone.left &&
            xFraction <= zone.right &&
            yFraction >= zone.top &&
            yFraction <= zone.bottom
    }

    /** Serializes for the single `swipe_dead_zone` DataStore/backup key. */
    fun encode(): String {
        val clean = sanitized()
        val flag = if (clean.enabled) "1" else "0"
        return "v1:$flag:${clean.centerXPercent}:${clean.widthPercent}:${clean.heightPercent}"
    }

    companion object {
        const val MIN_CENTER = 0
        const val MAX_CENTER = 100
        const val MIN_WIDTH = 10

        /** ≤ 80% width keeps at least a fifth of every row swipeable. */
        const val MAX_WIDTH = 80
        const val MIN_HEIGHT = 25
        const val MAX_HEIGHT = 100

        /**
         * Off by default (existing installs keep today's behaviour); the
         * tuning values are what the zone shows when first switched on: a
         * centred band over the middle 40% of the row's width and its full
         * height - wide enough to scroll from anywhere near the middle,
         * narrow enough that both row ends stay comfortably swipeable.
         */
        val DEFAULT = SwipeDeadZone(enabled = false, centerXPercent = 50, widthPercent = 40, heightPercent = 100)

        /** Lenient inverse of [encode]: anything malformed falls back to [DEFAULT]. */
        fun decode(stored: String?): SwipeDeadZone {
            if (stored == null) return DEFAULT
            val parts = stored.split(':')
            if (parts.size != 5 || parts[0] != "v1") return DEFAULT
            val enabled =
                when (parts[1]) {
                    "1" -> true
                    "0" -> false
                    else -> return DEFAULT
                }
            val center = parts[2].toIntOrNull() ?: return DEFAULT
            val width = parts[3].toIntOrNull() ?: return DEFAULT
            val height = parts[4].toIntOrNull() ?: return DEFAULT
            return SwipeDeadZone(enabled, center, width, height).sanitized()
        }
    }
}
