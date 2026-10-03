package app.clearsms.notification

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import androidx.core.graphics.ColorUtils
import androidx.core.graphics.drawable.IconCompat
import androidx.core.net.toUri
import app.clearsms.ui.components.AvatarStyle
import app.clearsms.ui.components.BUNDLED_LOGO_DIR
import app.clearsms.ui.components.BrandCategory
import app.clearsms.ui.components.BundledLogoCache
import app.clearsms.ui.components.avatarStyleFor
import app.clearsms.ui.components.plainAvatarColorArgb
import app.clearsms.ui.components.plainAvatarInitial
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Builds the large icon / [IconCompat] shown for a sender in notifications.
 *
 * The fallback chain is the SAME one the in-app avatars use (the tier is
 * literally picked by the shared [avatarStyleFor]): contact photo → bundled
 * asset logo (`assets/logos/<brandKey>.png`, drawn whole on a circular white
 * plate exactly like `SenderAvatar`) → generated brand tile (brand color +
 * monogram + category badge) → category-glyph monogram tile for
 * directory-known senders → letter tile. The letter tile is the inbox's own
 * `PlainAvatar` derivation (one initial, the `AVATAR_HUES` pastel - see
 * [tileKeyFor]), so a contact's avatar in the shade and on the launcher is
 * the one the inbox row shows. Every rendered bitmap is circular, matching
 * the single avatar shape used across the app.
 *
 * Caching: bundled logos are decoded and plated at most once per brand key
 * ([BundledLogoCache], which also memoizes misses so a corrupt or missing
 * asset costs one attempt); generated tiles are rendered at most once per
 * (monogram, color, badge) signature. Contact photos are deliberately not
 * memoized - the photo and the READ_CONTACTS grant can both change.
 *
 * Callers must invoke this off the main thread (every notifier is driven
 * from the receiver's IO-dispatched scope): the first lookup per key decodes
 * from assets or renders to a canvas. Any failure - corrupt asset, unknown
 * brand, unreadable photo - degrades to the next tier, never a crash, never
 * a blank icon.
 */
@Singleton
class SenderIconFactory internal constructor(
    private val context: Context,
    loadLogoAsset: (String) -> Bitmap?,
) {
    @Inject
    constructor(
        @ApplicationContext context: Context,
    ) : this(
        context = context,
        loadLogoAsset = { key ->
            context.assets
                .open("$BUNDLED_LOGO_DIR/$key.png")
                .use { BitmapFactory.decodeStream(it) }
        },
    )

    /** Decode-and-plate-once cache for bundled logo artwork, keyed by brand key. */
    private val logoPlates = BundledLogoCache<Bitmap> { key -> loadLogoAsset(key)?.let { logoPlate(it) } }

    /** Render-once cache for generated tiles, keyed by what they draw. */
    private val tiles = ConcurrentHashMap<TileKey, Bitmap>()

    /** Icon for [sender], for MessagingStyle [androidx.core.app.Person]s. */
    fun iconFor(sender: NotificationSender): IconCompat = IconCompat.createWithBitmap(largeIconFor(sender))

    /**
     * Large-icon bitmap for [sender] via the shared avatar chain. Total:
     * always returns a bitmap, whatever fails along the way.
     */
    fun largeIconFor(sender: NotificationSender): Bitmap =
        when (styleFor(sender)) {
            AvatarStyle.PHOTO -> contactPhotoBitmap(sender.photoUri) ?: tileFor(sender)
            AvatarStyle.BUNDLED -> sender.brandKey?.let(logoPlates::get) ?: tileFor(sender)
            AvatarStyle.BRAND, AvatarStyle.BRAND_MARK, AvatarStyle.PLAIN -> tileFor(sender)
        }

    /**
     * Launcher-shortcut avatar for [sender]: the SAME circular avatar
     * [largeIconFor] renders (so a shortcut looks like the inbox row and the
     * notification it will sit next to - one avatar chain, one shape, the
     * circle `AvatarDefaults.shape` pins for every in-app avatar), laid out
     * as an ADAPTIVE-icon square so the launcher masks it with its own icon
     * shape instead of wrapping a legacy bitmap in a white backdrop. The
     * avatar is inset to the adaptive safe zone over a plate in the tier's
     * own ground colour - the tile colour for generated tiles (so a letter
     * avatar reads as a full-bleed coloured icon, the Samsung Messages
     * look), white for a contact photo or bundled logo (their own plates).
     *
     * Returned as a bitmap, not an [IconCompat]: a long-lived shortcut may
     * not carry a bitmap icon (the system caches long-lived shortcuts and
     * refuses to cache bitmaps), so `ConversationShortcutIcons` writes this
     * bitmap to a file and hands the system a content-URI icon instead.
     */
    fun shortcutAvatarFor(sender: NotificationSender): Bitmap = adaptivePlate(largeIconFor(sender), plateColorFor(sender))

    /** The ground colour behind a shortcut avatar - see [shortcutIconFor]. */
    internal fun plateColorFor(sender: NotificationSender): Int =
        when (styleFor(sender)) {
            AvatarStyle.PHOTO, AvatarStyle.BUNDLED -> {
                android.graphics.Color.WHITE
            }

            // The plate follows the tile it frames, whichever tier drew it.
            AvatarStyle.BRAND, AvatarStyle.BRAND_MARK, AvatarStyle.PLAIN -> {
                tileKeyFor(sender).colorArgb
            }
        }

    /**
     * Which tier of the chain [sender] lands on - decided by the SAME
     * [avatarStyleFor] the UI uses, so notification and in-app identity can
     * never disagree on precedence. Internal so tests can pin the order.
     */
    internal fun styleFor(sender: NotificationSender): AvatarStyle =
        avatarStyleFor(
            richAvatars = true,
            photoUri = sender.photoUri?.takeIf { it.isNotBlank() },
            isKnownSender = sender.isKnownSender,
            hasBundledLogo = sender.brandKey?.let { logoPlates.get(it) } != null,
            hasBrand = sender.colorArgb != null,
        )

    private fun contactPhotoBitmap(photoUri: String?): Bitmap? {
        if (photoUri.isNullOrBlank()) return null
        return try {
            context.contentResolver
                .openInputStream(photoUri.toUri())
                ?.use { BitmapFactory.decodeStream(it) }
                ?.let { circleCrop(it) }
        } catch (_: Exception) {
            // Missing/unreadable thumbnail (or contacts permission revoked
            // between lookup and render): fall back to the generated tile.
            null
        }
    }

    private fun tileFor(sender: NotificationSender): Bitmap {
        val key = tileKeyFor(sender)
        return tiles.computeIfAbsent(key) { tileBitmap(it.monogram, it.colorArgb, it.badge) }
    }

    /**
     * What the generated tile for [sender] draws, decided by the tier the
     * sender lands on WITHOUT a photo or bundled artwork ([tileStyleFor]) -
     * the tile is also what a missing photo or corrupt logo degrades to, and
     * `SenderAvatar` degrades the same way (photo error → letter avatar,
     * missing logo → brand mark).
     *
     * - PLAIN (a saved contact, an unknown number): the inbox letter avatar's
     *   own derivation - ONE initial on the name's `AVATAR_HUES` pastel
     *   ([plainAvatarInitial] / [plainAvatarColorArgb]), composited over a
     *   neutral grey since there is no theme surface here. Same letter, same
     *   hue as the inbox row; only the surface differs.
     * - BRAND / BRAND_MARK: the brand monogram (or [initialsOf]) on the brand
     *   colour or the brand-mark hue wheel - `SenderBrandMark`'s facts.
     *
     * Internal so a test can pin the shortcut and notification tile to the
     * inbox derivation and catch any drift.
     */
    internal fun tileKeyFor(sender: NotificationSender): TileKey =
        when (tileStyleFor(sender)) {
            AvatarStyle.PLAIN ->
                TileKey(
                    monogram = plainAvatarInitial(sender.name),
                    colorArgb = plainAvatarColorArgb(sender.name, PLAIN_AVATAR_SURFACE_ARGB),
                    badge = badgeCharFor(sender.brandCategory),
                )

            AvatarStyle.BRAND, AvatarStyle.BRAND_MARK, AvatarStyle.PHOTO, AvatarStyle.BUNDLED ->
                TileKey(
                    monogram = sender.monogram,
                    colorArgb = sender.colorArgb ?: fallbackColorFor(sender.name),
                    badge = badgeCharFor(sender.brandCategory),
                )
        }

    /** [styleFor] with the photo and bundled-logo tiers taken away: the tier the generated tile stands in for. */
    private fun tileStyleFor(sender: NotificationSender): AvatarStyle =
        avatarStyleFor(
            richAvatars = true,
            photoUri = null,
            isKnownSender = sender.isKnownSender,
            hasBundledLogo = false,
            hasBrand = sender.colorArgb != null,
        )

    /** Everything a generated tile draws - the render-cache key. */
    internal data class TileKey(
        val monogram: String,
        val colorArgb: Int,
        val badge: Char?,
    )

    companion object {
        /** Rendered icon edge; the notification shade scales it down as needed. */
        internal const val ICON_SIZE_PX = 128

        /** Fraction of the plate kept as padding around a bundled logo. */
        private const val PLATE_PADDING_FRACTION = 0.10f

        /**
         * The surface a PLAIN letter tile's tint is laid over - the stand-in
         * for the inbox's themed `surfaceVariant`, which neither the shade
         * nor the launcher can follow. A neutral grey, so the composite keeps
         * exactly the inbox hue; light, so the letter is dark like the
         * inbox's `onSurface`. Internal so the parity test can reproduce it.
         */
        internal const val PLAIN_AVATAR_SURFACE_ARGB = 0xFFE6E6E6.toInt()

        /** Same hue wheel as the in-app brand marks (`SenderBrandMark`), for the BRAND_MARK tier. */
        private val FALLBACK_HUES = floatArrayOf(8f, 32f, 152f, 176f, 206f, 226f, 258f, 288f, 340f)

        /** Deterministic brand-mark tile color for directory-known senders without a curated brand color. */
        internal fun fallbackColorFor(name: String): Int =
            ColorUtils.HSLToColor(floatArrayOf(FALLBACK_HUES[Math.floorMod(name.hashCode(), FALLBACK_HUES.size)], 0.55f, 0.38f))

        /**
         * The category badge letter drawn on generated brand tiles -
         * the notification-canvas stand-in for the UI's glyph badge.
         */
        internal fun badgeCharFor(category: BrandCategory?): Char? =
            when (category) {
                BrandCategory.BANK -> 'B'
                BrandCategory.CARD -> 'C'
                BrandCategory.WALLET -> 'W'
                BrandCategory.TELECOM -> 'T'
                BrandCategory.ECOMMERCE -> 'S'
                BrandCategory.DELIVERY -> 'D'
                BrandCategory.GOVERNMENT -> 'G'
                BrandCategory.UTILITY -> 'U'
                BrandCategory.INVESTMENT -> 'I'
                BrandCategory.HEALTH -> 'H'
                BrandCategory.TRAVEL -> 'V'
                BrandCategory.OTHER, null -> null
            }

        /**
         * Generated tile: colored circle + monogram, plus an optional small
         * category badge at the bottom-right (white ring, deepened fill).
         */
        internal fun tileBitmap(
            monogram: String,
            colorArgb: Int,
            badge: Char? = null,
            sizePx: Int = ICON_SIZE_PX,
        ): Bitmap {
            val bitmap = monogramBitmap(monogram, colorArgb, sizePx)
            if (badge == null) return bitmap
            val canvas = Canvas(bitmap)
            val radius = sizePx * 0.17f
            val center = sizePx - radius - sizePx * 0.03f
            canvas.drawCircle(center, center, radius, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = android.graphics.Color.WHITE })
            val fill = ColorUtils.blendARGB(colorArgb, android.graphics.Color.BLACK, 0.25f)
            canvas.drawCircle(center, center, radius * 0.82f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = fill })
            val badgePaint =
                Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color =
                        if (ColorUtils.calculateLuminance(fill) > 0.4) {
                            android.graphics.Color.BLACK
                        } else {
                            android.graphics.Color.WHITE
                        }
                    typeface = Typeface.DEFAULT_BOLD
                    textSize = radius
                    textAlign = Paint.Align.CENTER
                }
            val baseline = center - (badgePaint.descent() + badgePaint.ascent()) / 2f
            canvas.drawText(badge.toString(), center, baseline, badgePaint)
            return bitmap
        }

        /**
         * Draws the monogram tile: colored circle plus bold centered text in
         * white or black - whichever contrasts better (WCAG luminance).
         */
        internal fun monogramBitmap(
            monogram: String,
            backgroundArgb: Int,
            sizePx: Int = ICON_SIZE_PX,
        ): Bitmap {
            val bitmap = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)
            val half = sizePx / 2f
            canvas.drawCircle(half, half, half, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = backgroundArgb })
            val textPaint =
                Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color =
                        if (ColorUtils.calculateLuminance(backgroundArgb) > 0.4) {
                            android.graphics.Color.BLACK
                        } else {
                            android.graphics.Color.WHITE
                        }
                    typeface = Typeface.DEFAULT_BOLD
                    textSize = sizePx * (if (monogram.length > 2) 0.30f else 0.42f)
                    textAlign = Paint.Align.CENTER
                }
            val baseline = half - (textPaint.descent() + textPaint.ascent()) / 2f
            canvas.drawText(monogram, half, baseline, textPaint)
            return bitmap
        }

        /**
         * Bundled logo on a circular white plate - the notification twin of
         * the in-app BUNDLED avatar (white background, logo fit whole inside
         * padding, everything clipped to the circle).
         */
        internal fun logoPlate(
            logo: Bitmap,
            sizePx: Int = ICON_SIZE_PX,
        ): Bitmap {
            val output = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(output)
            val half = sizePx / 2f
            canvas.clipPath(Path().apply { addCircle(half, half, half, Path.Direction.CW) })
            canvas.drawCircle(half, half, half, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = android.graphics.Color.WHITE })
            val padding = sizePx * PLATE_PADDING_FRACTION
            val content = sizePx - 2 * padding
            val scale = minOf(content / logo.width, content / logo.height)
            val width = logo.width * scale
            val height = logo.height * scale
            val left = (sizePx - width) / 2f
            val top = (sizePx - height) / 2f
            canvas.drawBitmap(
                logo,
                null,
                RectF(left, top, left + width, top + height),
                Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG),
            )
            return output
        }

        /**
         * Edge of the adaptive-icon bitmap handed to the launcher. 108dp is
         * the adaptive canvas; at 2x it is comfortably above every
         * launcher's icon size and well under ShortcutManager's max.
         */
        internal const val ADAPTIVE_SIZE_PX = 216

        /**
         * The adaptive-icon safe zone: the central 66/108 of the canvas is
         * guaranteed visible under every launcher mask (72/108 is the
         * largest the mask shows), so the circular avatar is scaled to it.
         */
        internal const val ADAPTIVE_SAFE_FRACTION = 66f / 108f

        /**
         * Square adaptive bitmap: [plateArgb] edge to edge, the circular
         * [avatar] centered and scaled to the safe zone. The launcher clips
         * the whole square to its mask, so the corners are never shown - the
         * plate only fills the ring between the avatar and the mask edge.
         */
        internal fun adaptivePlate(
            avatar: Bitmap,
            plateArgb: Int,
            sizePx: Int = ADAPTIVE_SIZE_PX,
        ): Bitmap {
            val output = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(output)
            canvas.drawColor(plateArgb)
            val diameter = sizePx * ADAPTIVE_SAFE_FRACTION
            val inset = (sizePx - diameter) / 2f
            canvas.drawBitmap(
                avatar,
                null,
                RectF(inset, inset, inset + diameter, inset + diameter),
                Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG),
            )
            return output
        }

        /** Center-crops [source] into a circle (the shape Person icons expect). */
        internal fun circleCrop(source: Bitmap): Bitmap {
            val size = minOf(source.width, source.height)
            val output = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(output)
            val shader = BitmapShader(source, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
            // Offset the shader so the CENTER of the source lands in the crop square.
            shader.setLocalMatrix(
                android.graphics.Matrix().apply {
                    setTranslate(-(source.width - size) / 2f, -(source.height - size) / 2f)
                },
            )
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.shader = shader }
            val half = size / 2f
            canvas.drawCircle(half, half, half, paint)
            return output
        }
    }
}
