package app.clearsms.mms

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.File

/**
 * Size discipline for outgoing MMS, applied when an attachment is staged
 * (not at send time) so the compose chips always show the bytes that will
 * actually travel and the over-limit error appears inline, immediately.
 * HOW MANY bytes may travel is the carrier's call - see [MmsSizeBudget];
 * the constants here bound the work of getting there.
 */
object MmsSizeLimits {
    /**
     * Largest source image accepted for staging. Recompressible images
     * are judged by pixels, not bytes (any photo recompresses under a
     * carrier budget at the smallest [EDGE_LADDER_PX] step), but the
     * staging copy still costs disk and I/O, so a line is drawn at 50 MB:
     * comfortably above the largest genuine phone-camera JPEG (a 200 MP
     * flagship photo is ~40 MB) while refusing pathological picks - the
     * 377 MB selection in issue #6 - before a single byte is copied.
     * Non-recompressible content is capped at the carrier budget
     * directly, because it travels as-is or not at all.
     */
    const val MAX_STAGED_IMAGE_BYTES = 50_000_000L

    /**
     * Longest-edge cap for recompressed images: 2048 px keeps a photo
     * crisp on any phone screen while cutting a 12 MP camera image to a
     * fraction of its size before JPEG quality even applies. This is the
     * FIRST rung of [EDGE_LADDER_PX] - a generous carrier never gets less.
     */
    const val MAX_IMAGE_EDGE_PX = 2048

    /** JPEG quality for recompressed images: visually clean, small files. First rung of [QUALITY_LADDER]. */
    const val JPEG_QUALITY = 80

    /**
     * Longest-edge steps tried, in order, until the image fits its byte
     * target. Each rung is decoded from the SOURCE FILE with a matching
     * `inSampleSize`, so peak memory only ever shrinks down the ladder
     * (issue #6's bound holds at every step). 320 px is the floor: below
     * it a photo stops being a photo, and refusing is more honest.
     */
    val EDGE_LADDER_PX = intArrayOf(MAX_IMAGE_EDGE_PX, 1536, 1024, 768, 512, 320)

    /**
     * JPEG qualities tried at each edge rung before stepping down an edge:
     * a modest quality drop at the current size usually beats a smaller
     * picture, a harsh one does not - so two steps, then shrink.
     */
    val QUALITY_LADDER = intArrayOf(JPEG_QUALITY, 60)
}

/**
 * Recompresses images to fit a byte target, working file-to-file so a
 * huge source is NEVER held in memory whole: the bounds pass uses
 * `inJustDecodeBounds`, every pixel decode is downsampled with
 * `inSampleSize`, and the JPEG re-encode streams straight into the target
 * file. Only static images are touched: GIFs (recompression would destroy
 * animation) and non-images pass through untouched. A JPEG/PNG/etc. is
 * downsampled to [MmsSizeLimits.MAX_IMAGE_EDGE_PX] on its longest edge
 * and re-encoded at [MmsSizeLimits.JPEG_QUALITY] - but only when that
 * actually helps: if the original file is already smaller (and fits), it
 * is kept. When the result is still over [maxBytes], the edge/quality
 * ladders are walked until it fits or the floor is reached; the caller
 * compares the returned file's length with its budget and refuses what
 * could not be made to fit.
 */
object ImageShrink {
    /** Whether [mimeType] is eligible for recompression. */
    fun isCompressible(mimeType: String): Boolean = mimeType.startsWith("image/") && mimeType != "image/gif"

    /**
     * The file (and mime type) to actually attach for [source] declared as
     * [mimeType], aiming at most [maxBytes]. When recompression helps, the
     * JPEG is written to [target] and returned; otherwise [source] is
     * returned unchanged and [target] is cleaned up. Non-compressible and
     * undecodable input always passes through as [source]. The result may
     * still exceed [maxBytes] when even the smallest ladder rung does -
     * the caller decides what that means.
     */
    fun shrink(
        source: File,
        mimeType: String,
        target: File,
        maxBytes: Long = Long.MAX_VALUE,
    ): ShrunkFile {
        if (!isCompressible(mimeType)) return ShrunkFile(source, mimeType)
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(source.path, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return ShrunkFile(source, mimeType)
        val longestEdge = maxOf(bounds.outWidth, bounds.outHeight)
        val needsResize = longestEdge > MmsSizeLimits.MAX_IMAGE_EDGE_PX

        var encoded = false
        for (edgeCap in MmsSizeLimits.EDGE_LADDER_PX) {
            // A rung larger than the picture itself re-encodes the same
            // pixels again; skip straight to the one that changes something.
            if (edgeCap != MmsSizeLimits.MAX_IMAGE_EDGE_PX && edgeCap >= longestEdge) continue
            val bitmap = decodeUnder(source, longestEdge, edgeCap) ?: break
            val scaled = scaleToEdgeCap(bitmap, edgeCap)
            try {
                for (quality in MmsSizeLimits.QUALITY_LADDER) {
                    target.outputStream().use { out -> scaled.compress(Bitmap.CompressFormat.JPEG, quality, out) }
                    encoded = true
                    if (target.length() <= maxBytes) return chooseBetween(source, mimeType, target, needsResize, maxBytes)
                }
            } finally {
                if (scaled !== bitmap) scaled.recycle()
                bitmap.recycle()
            }
        }
        // Nothing fit: hand back the smallest attempt (or the untouched
        // source when no decode succeeded) for the caller to judge.
        return if (encoded) chooseBetween(source, mimeType, target, needsResize, maxBytes) else ShrunkFile(source, mimeType)
    }

    /**
     * Keep the original when recompression did not help (a small,
     * already-efficient image that fits) UNLESS the dimensions had to
     * shrink or the original itself is over budget.
     */
    private fun chooseBetween(
        source: File,
        mimeType: String,
        target: File,
        needsResize: Boolean,
        maxBytes: Long,
    ): ShrunkFile =
        if (target.length() < source.length() || needsResize || source.length() > maxBytes) {
            ShrunkFile(target, "image/jpeg")
        } else {
            target.delete()
            ShrunkFile(source, mimeType)
        }

    /** Output of [shrink]: the file to attach and its (possibly new) mime. */
    data class ShrunkFile(
        val file: File,
        val mimeType: String,
    )

    /** Downsampled decode of [source] so its longest edge lands in `[edgeCap, 2*edgeCap)` - bounded memory. */
    private fun decodeUnder(
        source: File,
        longestEdge: Int,
        edgeCap: Int,
    ): Bitmap? {
        val options = BitmapFactory.Options().apply { inSampleSize = sampleSizeFor(longestEdge, edgeCap) }
        return BitmapFactory.decodeFile(source.path, options)
    }

    /** Power-of-two downsample so the decode itself stays within memory. */
    internal fun sampleSizeFor(
        longestEdge: Int,
        edgeCap: Int = MmsSizeLimits.MAX_IMAGE_EDGE_PX,
    ): Int {
        var sample = 1
        var edge = longestEdge
        while (edge / 2 >= edgeCap) {
            sample *= 2
            edge /= 2
        }
        return sample
    }

    /** Exact scale to [edgeCap] after the power-of-two decode. */
    private fun scaleToEdgeCap(
        bitmap: Bitmap,
        edgeCap: Int,
    ): Bitmap {
        val longest = maxOf(bitmap.width, bitmap.height)
        if (longest <= edgeCap) return bitmap
        val scale = edgeCap.toFloat() / longest
        return Bitmap.createScaledBitmap(
            bitmap,
            (bitmap.width * scale).toInt().coerceAtLeast(1),
            (bitmap.height * scale).toInt().coerceAtLeast(1),
            true,
        )
    }
}
