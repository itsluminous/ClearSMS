package app.clearsms.mms

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

/**
 * Compression matrix for outgoing MMS images, now file-to-file (issue #6:
 * staging must never hold the source in memory whole): a big JPEG lands
 * under the edge cap and shrinks, an already-tiny PNG passes through
 * untouched, a GIF is never recompressed (animation would be destroyed),
 * junk that does not decode passes through unchanged, and the power-of-two
 * decode sampling is exact.
 */
@RunWith(RobolectricTestRunner::class)
class ImageShrinkTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()

    private fun encodedBitmapFile(
        name: String,
        width: Int,
        height: Int,
        format: Bitmap.CompressFormat,
        quality: Int = 95,
    ): File {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        // Noise, so JPEG cannot compress it to nearly nothing.
        for (x in 0 until width step 7) {
            for (y in 0 until height step 7) {
                bitmap.setPixel(x, y, (x * 31 + y * 17) or 0xFF000000.toInt())
            }
        }
        val file = File(context.cacheDir, name)
        file.outputStream().use { bitmap.compress(format, quality, it) }
        bitmap.recycle()
        return file
    }

    private fun target(name: String): File = File(context.cacheDir, name)

    @Test
    fun `big jpeg is resized under the edge cap into the target file and shrinks`() {
        val original = encodedBitmapFile("big.jpg", 3000, 2000, Bitmap.CompressFormat.JPEG)
        val out = target("big.shrunk")

        val shrunk = ImageShrink.shrink(original, "image/jpeg", out)

        assertThat(shrunk.mimeType).isEqualTo("image/jpeg")
        assertThat(shrunk.file).isEqualTo(out)
        assertThat(shrunk.file.length()).isLessThan(original.length())
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(shrunk.file.path, bounds)
        assertThat(maxOf(bounds.outWidth, bounds.outHeight)).isAtMost(MmsSizeLimits.MAX_IMAGE_EDGE_PX)
    }

    @Test
    fun `small png that jpeg cannot improve passes through untouched and target is cleaned up`() {
        val original = encodedBitmapFile("tiny.png", 8, 8, Bitmap.CompressFormat.PNG, quality = 100)
        val out = target("tiny.shrunk")

        val shrunk = ImageShrink.shrink(original, "image/png", out)

        assertThat(shrunk.mimeType).isEqualTo("image/png")
        assertThat(shrunk.file).isEqualTo(original)
        assertThat(out.exists()).isFalse()
    }

    @Test
    fun `gif is never recompressed`() {
        val gif = File(context.cacheDir, "anim.gif")
        gif.writeBytes("GIF89a".toByteArray() + ByteArray(512) { it.toByte() })

        val shrunk = ImageShrink.shrink(gif, "image/gif", target("anim.shrunk"))

        assertThat(shrunk.mimeType).isEqualTo("image/gif")
        assertThat(shrunk.file).isEqualTo(gif)
        assertThat(ImageShrink.isCompressible("image/gif")).isFalse()
    }

    @Test
    fun `non-image and undecodable files pass through unchanged`() {
        val pdf = File(context.cacheDir, "doc.pdf").apply { writeBytes(ByteArray(64) { 3 }) }
        assertThat(ImageShrink.shrink(pdf, "application/pdf", target("pdf.shrunk")).file).isEqualTo(pdf)

        val junk = File(context.cacheDir, "junk.jpg").apply { writeBytes(ByteArray(64) { 9 }) }
        assertThat(ImageShrink.shrink(junk, "image/jpeg", target("junk.shrunk")).file).isEqualTo(junk)
    }

    @Test
    fun `sample size is the largest power of two keeping the decode at or above the edge cap`() {
        val cap = MmsSizeLimits.MAX_IMAGE_EDGE_PX
        // At or under the cap: no downsampling.
        assertThat(ImageShrink.sampleSizeFor(1)).isEqualTo(1)
        assertThat(ImageShrink.sampleSizeFor(cap)).isEqualTo(1)
        // Just under double: halving would undershoot the cap, so still 1.
        assertThat(ImageShrink.sampleSizeFor(2 * cap - 1)).isEqualTo(1)
        // Exactly double halves once.
        assertThat(ImageShrink.sampleSizeFor(2 * cap)).isEqualTo(2)
        assertThat(ImageShrink.sampleSizeFor(4 * cap)).isEqualTo(4)
        // A 377 MB-class monster (say 40000 px on an edge with cap 2048)
        // decodes at 1/16 - bounded memory regardless of source size.
        assertThat(ImageShrink.sampleSizeFor(40_000)).isEqualTo(16)
        // The decoded edge always stays >= cap (never undershoots) and
        // < 2*cap (never wastefully large).
        for (edge in intArrayOf(cap + 1, 3 * cap, 5 * cap, 100_000)) {
            val decoded = edge / ImageShrink.sampleSizeFor(edge)
            assertThat(decoded).isAtLeast(cap)
            assertThat(decoded).isLessThan(2 * cap)
        }
    }
}
