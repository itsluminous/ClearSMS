package app.clearsms.mms

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.ByteArrayOutputStream

/**
 * Compression matrix for outgoing MMS images: a big JPEG lands under the
 * edge cap and shrinks, an already-tiny PNG passes through untouched, a
 * GIF is never recompressed (animation would be destroyed), and junk that
 * does not decode passes through unchanged.
 */
@RunWith(RobolectricTestRunner::class)
class ImageShrinkTest {
    private fun encodedBitmap(
        width: Int,
        height: Int,
        format: Bitmap.CompressFormat,
        quality: Int = 95,
    ): ByteArray {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        // Noise, so JPEG cannot compress it to nearly nothing.
        for (x in 0 until width step 7) {
            for (y in 0 until height step 7) {
                bitmap.setPixel(x, y, (x * 31 + y * 17) or 0xFF000000.toInt())
            }
        }
        val out = ByteArrayOutputStream()
        bitmap.compress(format, quality, out)
        bitmap.recycle()
        return out.toByteArray()
    }

    @Test
    fun `big jpeg is resized under the edge cap and shrinks`() {
        val original = encodedBitmap(3000, 2000, Bitmap.CompressFormat.JPEG)

        val shrunk = ImageShrink.shrink(original, "image/jpeg")

        assertThat(shrunk.mimeType).isEqualTo("image/jpeg")
        assertThat(shrunk.data.size).isLessThan(original.size)
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(shrunk.data, 0, shrunk.data.size, bounds)
        assertThat(maxOf(bounds.outWidth, bounds.outHeight)).isAtMost(MmsSizeLimits.MAX_IMAGE_EDGE_PX)
    }

    @Test
    fun `small png that jpeg cannot improve passes through untouched`() {
        val original = encodedBitmap(8, 8, Bitmap.CompressFormat.PNG, quality = 100)

        val shrunk = ImageShrink.shrink(original, "image/png")

        assertThat(shrunk.mimeType).isEqualTo("image/png")
        assertThat(shrunk.data).isEqualTo(original)
    }

    @Test
    fun `gif is never recompressed`() {
        val gif = "GIF89a".toByteArray() + ByteArray(512) { it.toByte() }

        val shrunk = ImageShrink.shrink(gif, "image/gif")

        assertThat(shrunk.mimeType).isEqualTo("image/gif")
        assertThat(shrunk.data).isEqualTo(gif)
        assertThat(ImageShrink.isCompressible("image/gif")).isFalse()
    }

    @Test
    fun `non-image and undecodable bytes pass through unchanged`() {
        val pdf = ByteArray(64) { 3 }
        assertThat(ImageShrink.shrink(pdf, "application/pdf").data).isEqualTo(pdf)

        val junk = ByteArray(64) { 9 }
        assertThat(ImageShrink.shrink(junk, "image/jpeg").data).isEqualTo(junk)
    }
}
