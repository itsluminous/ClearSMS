package app.clearsms.notification

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Shader
import android.graphics.Typeface
import androidx.core.graphics.ColorUtils
import androidx.core.graphics.drawable.IconCompat
import androidx.core.net.toUri
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.abs

/**
 * Builds the [IconCompat] shown for a sender in notifications.
 *
 * Preference order: the saved contact's photo thumbnail (circle-cropped),
 * else an ORIGINAL generated tile — a circle in the brand's published color
 * (or a deterministic hash color) with the monogram on top. No third-party
 * logo artwork is ever bundled or rendered here; the tile mirrors the in-app
 * `SenderBrandMark`. Any decode failure degrades to the generated tile —
 * never a crash, never a blank icon.
 */
@Singleton
class SenderIconFactory
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
    ) {
        /** Icon for [sender]; photo when available, generated tile otherwise. */
        fun iconFor(sender: NotificationSender): IconCompat = contactPhotoIcon(sender.photoUri) ?: monogramIcon(sender)

        private fun contactPhotoIcon(photoUri: String?): IconCompat? {
            if (photoUri.isNullOrBlank()) return null
            return try {
                context.contentResolver
                    .openInputStream(photoUri.toUri())
                    ?.use { BitmapFactory.decodeStream(it) }
                    ?.let { IconCompat.createWithBitmap(circleCrop(it)) }
            } catch (_: Exception) {
                // Missing/unreadable thumbnail (or contacts permission revoked
                // between lookup and render): fall back to the generated tile.
                null
            }
        }

        private fun monogramIcon(sender: NotificationSender): IconCompat =
            IconCompat.createWithBitmap(
                monogramBitmap(
                    monogram = sender.monogram,
                    backgroundArgb = sender.colorArgb ?: fallbackColorFor(sender.name),
                ),
            )

        companion object {
            /** Rendered tile edge; notification shade scales it down as needed. */
            internal const val ICON_SIZE_PX = 96

            /** Same hue wheel as the in-app brand marks, for visual consistency. */
            private val FALLBACK_HUES = floatArrayOf(8f, 32f, 152f, 176f, 206f, 226f, 258f, 288f, 340f)

            /** Deterministic tile color for senders without a curated brand color. */
            internal fun fallbackColorFor(name: String): Int =
                ColorUtils.HSLToColor(floatArrayOf(FALLBACK_HUES[abs(name.hashCode()) % FALLBACK_HUES.size], 0.55f, 0.38f))

            /**
             * Draws the monogram tile: colored circle plus bold centered text in
             * white or black — whichever contrasts better (WCAG luminance).
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
