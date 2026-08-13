package app.clearsms.mms

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.ByteArrayOutputStream

/**
 * Size discipline for outgoing MMS, applied when an attachment is staged
 * (not at send time) so the compose chips always show the bytes that will
 * actually travel and the over-limit error appears inline, immediately.
 */
object MmsSizeLimits {
    /**
     * Total outgoing message budget: 1 MB (decimal, to stay under binary
     * 1 MiB checks). Carrier MMSC acceptance caps commonly sit between
     * 600 KB and 1.2 MB; 1 MB is the widely-interoperable modern default
     * that still leaves headroom for the PDU's header overhead. A message
     * whose parts exceed this after compression is refused honestly
     * rather than sent to die at the MMSC.
     */
    const val TOTAL_BUDGET_BYTES = 1_000_000L

    /**
     * Longest-edge cap for recompressed images: 2048 px keeps a photo
     * crisp on any phone screen while cutting a 12 MP camera image to a
     * fraction of its size before JPEG quality even applies.
     */
    const val MAX_IMAGE_EDGE_PX = 2048

    /** JPEG quality for recompressed images: visually clean, small files. */
    const val JPEG_QUALITY = 80
}

/**
 * Recompresses images to fit carrier MMS limits. Only static images are
 * touched: GIFs (recompression would destroy animation) and non-images
 * pass through byte-identical. A JPEG/PNG/etc. is decoded, downsampled to
 * [MmsSizeLimits.MAX_IMAGE_EDGE_PX] on its longest edge, and re-encoded
 * as JPEG at [MmsSizeLimits.JPEG_QUALITY] - but only when that actually
 * helps: if the original bytes are already smaller, they are kept.
 */
object ImageShrink {
    /** Whether [mimeType] is eligible for recompression. */
    fun isCompressible(mimeType: String): Boolean = mimeType.startsWith("image/") && mimeType != "image/gif"

    /**
     * The bytes (and mime type) to actually attach for [data] declared as
     * [mimeType]. Non-compressible input, undecodable input, and input
     * the recompression cannot improve are returned unchanged.
     */
    fun shrink(
        data: ByteArray,
        mimeType: String,
    ): Shrunk {
        if (!isCompressible(mimeType)) return Shrunk(data, mimeType)
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(data, 0, data.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return Shrunk(data, mimeType)

        val needsResize = maxOf(bounds.outWidth, bounds.outHeight) > MmsSizeLimits.MAX_IMAGE_EDGE_PX
        val options =
            BitmapFactory.Options().apply {
                inSampleSize = sampleSizeFor(maxOf(bounds.outWidth, bounds.outHeight))
            }
        val bitmap = BitmapFactory.decodeByteArray(data, 0, data.size, options) ?: return Shrunk(data, mimeType)
        val scaled = scaleToEdgeCap(bitmap)
        val encoded = ByteArrayOutputStream()
        scaled.compress(Bitmap.CompressFormat.JPEG, MmsSizeLimits.JPEG_QUALITY, encoded)
        if (scaled !== bitmap) scaled.recycle()
        bitmap.recycle()
        val jpeg = encoded.toByteArray()
        // Keep the original when recompression did not help (a small,
        // already-efficient image) UNLESS the dimensions had to shrink.
        return if (jpeg.size < data.size || needsResize) Shrunk(jpeg, "image/jpeg") else Shrunk(data, mimeType)
    }

    /** Output of [shrink]: the bytes to attach and their (possibly new) mime. */
    data class Shrunk(
        val data: ByteArray,
        val mimeType: String,
    ) {
        override fun equals(other: Any?): Boolean = other is Shrunk && other.mimeType == mimeType && other.data.contentEquals(data)

        override fun hashCode(): Int = 31 * mimeType.hashCode() + data.contentHashCode()
    }

    /** Power-of-two downsample so the decode itself stays within memory. */
    private fun sampleSizeFor(longestEdge: Int): Int {
        var sample = 1
        var edge = longestEdge
        while (edge / 2 >= MmsSizeLimits.MAX_IMAGE_EDGE_PX) {
            sample *= 2
            edge /= 2
        }
        return sample
    }

    /** Exact scale to the edge cap after the power-of-two decode. */
    private fun scaleToEdgeCap(bitmap: Bitmap): Bitmap {
        val longest = maxOf(bitmap.width, bitmap.height)
        if (longest <= MmsSizeLimits.MAX_IMAGE_EDGE_PX) return bitmap
        val scale = MmsSizeLimits.MAX_IMAGE_EDGE_PX.toFloat() / longest
        return Bitmap.createScaledBitmap(
            bitmap,
            (bitmap.width * scale).toInt().coerceAtLeast(1),
            (bitmap.height * scale).toInt().coerceAtLeast(1),
            true,
        )
    }
}
