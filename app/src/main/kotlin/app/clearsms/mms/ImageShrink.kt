package app.clearsms.mms

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.File

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
     * Largest source image accepted for staging. Recompressible images
     * are judged by pixels, not bytes (any photo recompresses under
     * [TOTAL_BUDGET_BYTES] at [MAX_IMAGE_EDGE_PX]/[JPEG_QUALITY]), but the
     * staging copy still costs disk and I/O, so a line is drawn at 50 MB:
     * comfortably above the largest genuine phone-camera JPEG (a 200 MP
     * flagship photo is ~40 MB) while refusing pathological picks - the
     * 377 MB selection in issue #6 - before a single byte is copied.
     * Non-recompressible content is capped at [TOTAL_BUDGET_BYTES]
     * directly, because it travels as-is or not at all.
     */
    const val MAX_STAGED_IMAGE_BYTES = 50_000_000L

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
 * Recompresses images to fit carrier MMS limits, working file-to-file so
 * a huge source is NEVER held in memory whole: the bounds pass uses
 * `inJustDecodeBounds`, the pixel decode is downsampled with
 * `inSampleSize`, and the JPEG re-encode streams straight into the target
 * file. Only static images are touched: GIFs (recompression would destroy
 * animation) and non-images pass through untouched. A JPEG/PNG/etc. is
 * downsampled to [MmsSizeLimits.MAX_IMAGE_EDGE_PX] on its longest edge
 * and re-encoded at [MmsSizeLimits.JPEG_QUALITY] - but only when that
 * actually helps: if the original file is already smaller, it is kept.
 */
object ImageShrink {
    /** Whether [mimeType] is eligible for recompression. */
    fun isCompressible(mimeType: String): Boolean = mimeType.startsWith("image/") && mimeType != "image/gif"

    /**
     * The file (and mime type) to actually attach for [source] declared as
     * [mimeType]. When recompression helps, the JPEG is written to
     * [target] and returned; otherwise [source] is returned unchanged and
     * [target] is cleaned up. Non-compressible and undecodable input
     * always passes through as [source].
     */
    fun shrink(
        source: File,
        mimeType: String,
        target: File,
    ): ShrunkFile {
        if (!isCompressible(mimeType)) return ShrunkFile(source, mimeType)
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(source.path, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return ShrunkFile(source, mimeType)

        val needsResize = maxOf(bounds.outWidth, bounds.outHeight) > MmsSizeLimits.MAX_IMAGE_EDGE_PX
        val options =
            BitmapFactory.Options().apply {
                inSampleSize = sampleSizeFor(maxOf(bounds.outWidth, bounds.outHeight))
            }
        val bitmap = BitmapFactory.decodeFile(source.path, options) ?: return ShrunkFile(source, mimeType)
        val scaled = scaleToEdgeCap(bitmap)
        try {
            target.outputStream().use { out ->
                scaled.compress(Bitmap.CompressFormat.JPEG, MmsSizeLimits.JPEG_QUALITY, out)
            }
        } finally {
            if (scaled !== bitmap) scaled.recycle()
            bitmap.recycle()
        }
        // Keep the original when recompression did not help (a small,
        // already-efficient image) UNLESS the dimensions had to shrink.
        return if (target.length() < source.length() || needsResize) {
            ShrunkFile(target, "image/jpeg")
        } else {
            target.delete()
            ShrunkFile(source, mimeType)
        }
    }

    /** Output of [shrink]: the file to attach and its (possibly new) mime. */
    data class ShrunkFile(
        val file: File,
        val mimeType: String,
    )

    /** Power-of-two downsample so the decode itself stays within memory. */
    internal fun sampleSizeFor(longestEdge: Int): Int {
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
