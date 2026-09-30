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
 * junk that does not decode passes through unchanged, the power-of-two
 * decode sampling is exact - and (issue #51) a byte target walks the
 * edge/quality ladder until the image fits, without ever decoding more
 * pixels than the first rung does.
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

    private fun noisyImage(
        name: String,
        width: Int,
        height: Int,
    ): File {
        val random = java.util.Random(7)
        val pixels = IntArray(width * height) { random.nextInt() or 0xFF000000.toInt() }
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        bitmap.setPixels(pixels, 0, width, 0, 0, width, height)
        val file = File(context.cacheDir, name)
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 95, it) }
        bitmap.recycle()
        return file
    }

    private fun longestEdge(file: File): Int {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, bounds)
        return maxOf(bounds.outWidth, bounds.outHeight)
    }

    @Test
    fun `a byte target walks the ladder down until the image fits`() {
        val original = noisyImage("ladder.jpg", 3000, 2000)
        val out = target("ladder.shrunk")

        val shrunk = ImageShrink.shrink(original, "image/jpeg", out, maxBytes = 299_008L)

        assertThat(shrunk.file).isEqualTo(out)
        assertThat(shrunk.mimeType).isEqualTo("image/jpeg")
        assertThat(out.length()).isAtMost(299_008L)
        val edge = longestEdge(out)
        assertThat(edge).isIn(MmsSizeLimits.EDGE_LADDER_PX.toList())
        assertThat(edge).isLessThan(MmsSizeLimits.MAX_IMAGE_EDGE_PX)
    }

    @Test
    fun `a target the first rung already meets changes nothing about the picture`() {
        val original = noisyImage("first-rung.jpg", 2600, 1800)
        val unlimited = ImageShrink.shrink(original, "image/jpeg", target("unlimited.shrunk"))
        val roomy = ImageShrink.shrink(original, "image/jpeg", target("roomy.shrunk"), maxBytes = unlimited.file.length() + 1)

        assertThat(roomy.file.length()).isEqualTo(unlimited.file.length())
        assertThat(longestEdge(roomy.file)).isEqualTo(MmsSizeLimits.MAX_IMAGE_EDGE_PX)
    }

    @Test
    fun `a small image already under target but over budget as-is is re-encoded rather than kept`() {
        // A PNG of noise is far larger than its JPEG; asking for fewer bytes
        // than the PNG has must return the JPEG even though no resize was needed.
        val bitmap = Bitmap.createBitmap(400, 300, Bitmap.Config.ARGB_8888)
        val random = java.util.Random(3)
        bitmap.setPixels(IntArray(400 * 300) { random.nextInt() or 0xFF000000.toInt() }, 0, 400, 0, 0, 400, 300)
        val png = File(context.cacheDir, "noise.png")
        png.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()

        val shrunk = ImageShrink.shrink(png, "image/png", target("noise-png.shrunk"), maxBytes = png.length() - 1)

        assertThat(shrunk.mimeType).isEqualTo("image/jpeg")
        assertThat(shrunk.file.length()).isLessThan(png.length())
    }

    @Test
    fun `an impossible target returns the smallest rung for the caller to refuse - never a crash or an empty file`() {
        val original = noisyImage("impossible.jpg", 1200, 900)

        val shrunk = ImageShrink.shrink(original, "image/jpeg", target("impossible.shrunk"), maxBytes = 1_024L)

        assertThat(shrunk.file.length()).isGreaterThan(1_024L)
        assertThat(longestEdge(shrunk.file)).isEqualTo(MmsSizeLimits.EDGE_LADDER_PX.last())
    }

    @Test
    fun `every ladder rung decodes at or under the first rung's pixel count - the issue 6 memory bound holds all the way down`() {
        val first = MmsSizeLimits.EDGE_LADDER_PX.first()
        assertThat(first).isEqualTo(MmsSizeLimits.MAX_IMAGE_EDGE_PX)
        assertThat(MmsSizeLimits.EDGE_LADDER_PX.toList()).isInStrictOrder(compareByDescending<Int> { it })
        assertThat(MmsSizeLimits.QUALITY_LADDER.first()).isEqualTo(MmsSizeLimits.JPEG_QUALITY)
        for (edge in intArrayOf(40_000, 5_000, 3_000, 2_049)) {
            val firstDecoded = edge / ImageShrink.sampleSizeFor(edge, first)
            MmsSizeLimits.EDGE_LADDER_PX.forEach { rung ->
                val decoded = edge / ImageShrink.sampleSizeFor(edge, rung)
                assertThat(decoded).isAtMost(firstDecoded)
                assertThat(decoded).isAtLeast(rung)
                assertThat(decoded).isLessThan(2 * rung)
            }
        }
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
