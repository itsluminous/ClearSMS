package app.clearsms.mms

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import java.io.File
import java.io.FileNotFoundException
import java.io.InputStream

/**
 * The issue #6 fix: staging STREAMS the picked content through a bounded
 * buffer (never `readBytes()`), refuses over-cap content up-front from the
 * provider-declared size without opening it, backstops undeclared sizes
 * mid-copy - and (issue #51) derives its caps from what the sending SIM's
 * CARRIER can actually carry: images are compressed to fit that limit,
 * pass-through content is refused against it with the limit named.
 */
@RunWith(RobolectricTestRunner::class)
class OutgoingAttachmentStagerTest {
    private lateinit var context: Context
    private lateinit var carrier: FakeCarrierMmsLimits
    private lateinit var stager: OutgoingAttachmentStager

    /** The reporter's carrier: the AOSP default 300 KiB. */
    private val defaultBudget = MmsSizeBudget.forCarrier(307_200)

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        carrier = FakeCarrierMmsLimits(307_200)
        stager = OutgoingAttachmentStager(context, carrier)
    }

    private fun stagingDir(): File = File(File(context.filesDir, "mms"), "compose")

    private fun stage(uri: Uri): StagingResult = stager.stage(uri, defaultBudget)

    /** A [width]x[height] image of per-pixel noise - the worst case for JPEG - encoded to a file. */
    private fun noisyImage(
        name: String,
        width: Int,
        height: Int,
        format: android.graphics.Bitmap.CompressFormat = android.graphics.Bitmap.CompressFormat.JPEG,
    ): File {
        val random = java.util.Random(42)
        val pixels = IntArray(width * height) { random.nextInt() or 0xFF000000.toInt() }
        val bitmap = android.graphics.Bitmap.createBitmap(width, height, android.graphics.Bitmap.Config.ARGB_8888)
        bitmap.setPixels(pixels, 0, width, 0, 0, width, height)
        val file = File(context.cacheDir, name)
        file.outputStream().use { bitmap.compress(format, 95, it) }
        bitmap.recycle()
        return file
    }

    private fun longestEdge(file: File): Int {
        val bounds =
            android.graphics.BitmapFactory
                .Options()
                .apply { inJustDecodeBounds = true }
        android.graphics.BitmapFactory.decodeFile(file.path, bounds)
        return maxOf(bounds.outWidth, bounds.outHeight)
    }

    /**
     * Serves [total] synthetic bytes WITHOUT ever holding them, recording
     * the largest single read request - the indirect peak-memory probe:
     * a bounded-buffer copy never asks for more than its buffer.
     */
    private class LazyStream(
        private val total: Long,
    ) : InputStream() {
        var maxChunkRequested = 0

        private var served = 0L

        override fun read(): Int =
            if (served < total) {
                served++
                7
            } else {
                -1
            }

        override fun read(
            b: ByteArray,
            off: Int,
            len: Int,
        ): Int {
            maxChunkRequested = maxOf(maxChunkRequested, len)
            if (served >= total) return -1
            val n = minOf(len.toLong(), total - served).toInt()
            b.fill(7, off, off + n)
            served += n
            return n
        }
    }

    // -- Streaming ---------------------------------------------------------

    @Test
    fun `a 10 MB source streams through a small buffer, never loaded whole`() {
        val tenMb = 10_000_000L
        val stream = LazyStream(tenMb)
        val uri = Uri.parse("content://fake/pic.jpg")
        shadowOf(context.contentResolver).registerInputStream(uri, stream)

        // A generous carrier, so the undecodable 10 MB "image" is not refused for size.
        val result = stager.stage(uri, MmsSizeBudget.forCarrier(20_000_000))

        // Junk that declares image/jpeg but does not decode passes through
        // ImageShrink untouched - the copy itself is what is under test.
        val staged = (result as StagingResult.Staged).attachment
        assertThat(staged.file.length()).isEqualTo(tenMb)
        // The peak single read request bounds the working buffer: a
        // whole-content allocation would be impossible under this ceiling.
        assertThat(stream.maxChunkRequested).isAtMost(64 * 1024)
    }

    @Test
    fun `copyBounded streams within the cap and reports the copied count`() {
        val tenMb = 10_000_000L
        val stream = LazyStream(tenMb)
        val target = File(context.cacheDir, "copy.bin")

        val copied = copyBounded(stream, target, capBytes = tenMb)

        assertThat(copied).isEqualTo(tenMb)
        assertThat(target.length()).isEqualTo(tenMb)
        assertThat(stream.maxChunkRequested).isAtMost(64 * 1024)
    }

    @Test
    fun `copyBounded aborts past the cap instead of copying everything`() {
        val stream = LazyStream(10_000_000L)
        val target = File(context.cacheDir, "aborted.bin")

        val copied = copyBounded(stream, target, capBytes = 1_000_000L)

        assertThat(copied).isEqualTo(-1L)
        // It stopped reading right after crossing the cap, not at the end.
        assertThat(target.length()).isLessThan(1_100_000L)
    }

    // -- The size-cap decision ----------------------------------------------

    @Test
    fun `caps derive from the carrier budget - pass-through content gets exactly what remains of it`() {
        val remaining = defaultBudget.attachmentTargetBytes
        // No transcoder exists, so video travels as-is or not at all.
        assertThat(stager.stagingCapBytes("video/mp4", remaining)).isEqualTo(remaining)
        assertThat(stager.stagingCapBytes("application/pdf", remaining)).isEqualTo(remaining)
        // GIFs are never recompressed (animation), so they are pass-through too.
        assertThat(stager.stagingCapBytes("image/gif", remaining)).isEqualTo(remaining)
        // A generous carrier's remaining budget flows through unchanged.
        assertThat(stager.stagingCapBytes("video/mp4", 1_040_384L)).isEqualTo(1_040_384L)
        // Nothing left: nothing more may pass through.
        assertThat(stager.stagingCapBytes("application/pdf", -50L)).isEqualTo(0L)
        // Recompressible images are judged by pixels, so their staging cap
        // is the larger camera-JPEG ceiling regardless of the carrier.
        assertThat(stager.stagingCapBytes("image/jpeg", remaining)).isEqualTo(MmsSizeLimits.MAX_STAGED_IMAGE_BYTES)
        assertThat(stager.stagingCapBytes("image/png", remaining)).isEqualTo(MmsSizeLimits.MAX_STAGED_IMAGE_BYTES)
    }

    @Test
    fun `the budget comes from the chosen SIM's carrier config, or the AOSP default when the platform will not say`() {
        carrier.maxMessageSizeBytes = 1_048_576
        assertThat(stager.budgetFor(7).limitBytes).isEqualTo(1_048_576L)
        assertThat(carrier.asked).containsExactly(7)

        carrier.maxMessageSizeBytes = null
        val unknown = stager.budgetFor(null)
        assertThat(unknown.limitKnown).isFalse()
        assertThat(unknown.limitBytes).isEqualTo(307_200L)
    }

    @Test
    fun `oversized declared content is refused before a single byte is read`() {
        Robolectric.buildContentProvider(HugeVideoProvider::class.java).create(AUTHORITY)
        HugeVideoProvider.opened = false
        val uri = Uri.parse("content://$AUTHORITY/holiday.mp4")

        val result = stage(uri)

        assertThat(result).isEqualTo(StagingResult.TooLarge(307_200L))
        assertThat(HugeVideoProvider.opened).isFalse()
        assertThat(stagingDir().listFiles().orEmpty()).isEmpty()
    }

    @Test
    fun `undeclared oversized content hits the mid-copy backstop with no leftovers`() {
        // 2 MB of video with NO size column: the bounded copy must refuse it.
        val uri = Uri.parse("content://fake/clip.mp4")
        shadowOf(context.contentResolver).registerInputStream(uri, LazyStream(2_000_000L))

        val result = stage(uri)

        assertThat(result).isEqualTo(StagingResult.TooLarge(307_200L))
        assertThat(stagingDir().listFiles().orEmpty()).isEmpty()
    }

    @Test
    fun `empty content is unreadable, not staged`() {
        val uri = Uri.parse("content://fake/empty.jpg")
        shadowOf(context.contentResolver).registerInputStream(uri, LazyStream(0))

        assertThat(stage(uri)).isEqualTo(StagingResult.Unreadable)
        assertThat(stagingDir().listFiles().orEmpty()).isEmpty()
    }

    @Test
    fun `unopenable uri is unreadable`() {
        assertThat(stage(Uri.parse("file:///nonexistent/nope.jpg")))
            .isEqualTo(StagingResult.Unreadable)
    }

    // -- Compression on staging ---------------------------------------------

    @Test
    fun `a real oversized jpeg is recompressed on staging`() {
        val bitmap = android.graphics.Bitmap.createBitmap(3000, 2000, android.graphics.Bitmap.Config.ARGB_8888)
        for (x in 0 until 3000 step 7) {
            for (y in 0 until 2000 step 7) {
                bitmap.setPixel(x, y, (x * 31 + y * 17) or 0xFF000000.toInt())
            }
        }
        val source = File(context.cacheDir, "photo.jpg")
        source.outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG, 95, it) }
        bitmap.recycle()

        val result = stage(Uri.fromFile(source))

        val staged = (result as StagingResult.Staged).attachment
        assertThat(staged.mimeType).isEqualTo("image/jpeg")
        assertThat(staged.sizeBytes).isLessThan(source.length())
        assertThat(staged.file.length()).isEqualTo(staged.sizeBytes)
    }

    // -- Fitting the carrier's limit (issue #51) ---------------------------

    @Test
    fun `a large image is compressed to fit the reporter's 307 200-byte carrier limit`() {
        // Per-pixel noise: at 2048 px this JPEG is several MB - it MUST walk the ladder.
        val source = noisyImage("noise.jpg", 3000, 2000)
        assertThat(source.length()).isGreaterThan(1_000_000L)

        val result = stage(Uri.fromFile(source))

        val staged = (result as StagingResult.Staged).attachment
        assertThat(staged.mimeType).isEqualTo("image/jpeg")
        assertThat(staged.sizeBytes).isAtMost(defaultBudget.attachmentTargetBytes)
        assertThat(staged.sizeBytes).isAtMost(299_008L)
        // Still a real, decodable picture.
        assertThat(longestEdge(staged.file)).isAtLeast(320)
        assertThat(staged.file.length()).isEqualTo(staged.sizeBytes)
    }

    @Test
    fun `a generous carrier is not compressed more than it needs to be`() {
        val source = noisyImage("noise-generous.jpg", 2600, 1800)
        // What the first ladder rung alone produces (2048 px at quality 80).
        val firstRung = ImageShrink.shrink(source, "image/jpeg", File(context.cacheDir, "rung1.jpg"))
        val firstRungBytes = firstRung.file.length()
        assertThat(longestEdge(firstRung.file)).isEqualTo(2048)

        val generous =
            (
                stager.stage(
                    Uri.fromFile(source),
                    MmsSizeBudget.forCarrier((firstRungBytes + 100_000L).toInt()),
                ) as StagingResult.Staged
            ).attachment
        val strict = (stage(Uri.fromFile(source)) as StagingResult.Staged).attachment

        // Generous: the very same first-rung image, full 2048 px edge.
        assertThat(generous.sizeBytes).isEqualTo(firstRungBytes)
        assertThat(longestEdge(generous.file)).isEqualTo(2048)
        // Strict: fits 300 KiB, necessarily smaller.
        assertThat(strict.sizeBytes).isAtMost(299_008L)
        assertThat(strict.sizeBytes).isLessThan(generous.sizeBytes)
        assertThat(longestEdge(strict.file)).isLessThan(2048)
    }

    @Test
    fun `an image is compressed into what REMAINS of the budget after earlier attachments`() {
        val source = noisyImage("noise-second.jpg", 2000, 1500)

        val result = stager.stage(Uri.fromFile(source), defaultBudget, usedBytes = 100_000L)

        val staged = (result as StagingResult.Staged).attachment
        assertThat(staged.sizeBytes).isAtMost(defaultBudget.attachmentTargetBytes - 100_000L)
    }

    @Test
    fun `a non-compressible file over the carrier limit is refused up front, naming the limit`() {
        val pdf = File(context.cacheDir, "big.pdf").apply { writeBytes(ByteArray(310_000) { 5 }) }

        val result = stage(Uri.fromFile(pdf))

        assertThat(result).isEqualTo(StagingResult.TooLarge(307_200L))
        assertThat(stagingDir().listFiles().orEmpty()).isEmpty()
        // The same file on a 1 MiB carrier goes through untouched.
        val generous = stager.stage(Uri.fromFile(pdf), MmsSizeBudget.forCarrier(1_048_576))
        assertThat((generous as StagingResult.Staged).attachment.sizeBytes).isEqualTo(310_000L)
    }

    @Test
    fun `an image that cannot be brought under the limit is refused with the limit, leaving nothing behind`() {
        val source = noisyImage("noise-tiny-budget.jpg", 1200, 900)
        // A 1 KiB target: even the 320 px floor of the ladder is far larger.
        val impossible = MmsSizeBudget.forCarrier(9_000)
        assertThat(impossible.attachmentTargetBytes).isEqualTo(1_024L)

        val result = stager.stage(Uri.fromFile(source), impossible)

        assertThat(result).isEqualTo(StagingResult.TooLarge(9_000L))
        assertThat(stagingDir().listFiles().orEmpty()).isEmpty()
    }

    @Test
    fun `the unknown-limit fallback sizes to the AOSP default rather than the old 1 MB budget`() {
        val source = noisyImage("noise-unknown.jpg", 3000, 2000)

        val result = stager.stage(Uri.fromFile(source), MmsSizeBudget.forCarrier(null))

        val staged = (result as StagingResult.Staged).attachment
        assertThat(staged.sizeBytes).isAtMost(299_008L)
    }

    /** Declares a 400 MB video and screams if anyone actually opens it. */
    class HugeVideoProvider : ContentProvider() {
        override fun onCreate(): Boolean = true

        override fun query(
            uri: Uri,
            projection: Array<out String>?,
            selection: String?,
            selectionArgs: Array<out String>?,
            sortOrder: String?,
        ): Cursor =
            MatrixCursor(arrayOf(OpenableColumns.SIZE)).apply {
                addRow(arrayOf(400_000_000L))
            }

        override fun getType(uri: Uri): String = "video/mp4"

        override fun openFile(
            uri: Uri,
            mode: String,
        ): ParcelFileDescriptor {
            opened = true
            throw FileNotFoundException("refused content must never be opened")
        }

        override fun insert(
            uri: Uri,
            values: ContentValues?,
        ): Uri? = null

        override fun delete(
            uri: Uri,
            selection: String?,
            selectionArgs: Array<out String>?,
        ): Int = 0

        override fun update(
            uri: Uri,
            values: ContentValues?,
            selection: String?,
            selectionArgs: Array<out String>?,
        ): Int = 0

        companion object {
            var opened = false
        }
    }

    private companion object {
        const val AUTHORITY = "app.clearsms.test.hugevideos"
    }
}
