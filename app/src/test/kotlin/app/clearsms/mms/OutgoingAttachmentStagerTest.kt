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
 * mid-copy, and derives its caps from what MMS can actually carry.
 */
@RunWith(RobolectricTestRunner::class)
class OutgoingAttachmentStagerTest {
    private lateinit var context: Context
    private lateinit var stager: OutgoingAttachmentStager

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        stager = OutgoingAttachmentStager(context)
    }

    private fun stagingDir(): File = File(File(context.filesDir, "mms"), "compose")

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

        val result = stager.stage(uri)

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
    fun `caps derive from the MMS budget - pass-through content gets exactly it`() {
        // No transcoder exists, so video travels as-is or not at all.
        assertThat(stager.stagingCapBytes("video/mp4")).isEqualTo(MmsSizeLimits.TOTAL_BUDGET_BYTES)
        assertThat(stager.stagingCapBytes("application/pdf")).isEqualTo(MmsSizeLimits.TOTAL_BUDGET_BYTES)
        // GIFs are never recompressed (animation), so they are pass-through too.
        assertThat(stager.stagingCapBytes("image/gif")).isEqualTo(MmsSizeLimits.TOTAL_BUDGET_BYTES)
        // Recompressible images are judged by pixels, so their staging cap
        // is the larger camera-JPEG ceiling.
        assertThat(stager.stagingCapBytes("image/jpeg")).isEqualTo(MmsSizeLimits.MAX_STAGED_IMAGE_BYTES)
        assertThat(stager.stagingCapBytes("image/png")).isEqualTo(MmsSizeLimits.MAX_STAGED_IMAGE_BYTES)
    }

    @Test
    fun `oversized declared content is refused before a single byte is read`() {
        Robolectric.buildContentProvider(HugeVideoProvider::class.java).create(AUTHORITY)
        HugeVideoProvider.opened = false
        val uri = Uri.parse("content://$AUTHORITY/holiday.mp4")

        val result = stager.stage(uri)

        assertThat(result).isEqualTo(StagingResult.TooLarge)
        assertThat(HugeVideoProvider.opened).isFalse()
        assertThat(stagingDir().listFiles().orEmpty()).isEmpty()
    }

    @Test
    fun `undeclared oversized content hits the mid-copy backstop with no leftovers`() {
        // 2 MB of video with NO size column: the bounded copy must refuse it.
        val uri = Uri.parse("content://fake/clip.mp4")
        shadowOf(context.contentResolver).registerInputStream(uri, LazyStream(2_000_000L))

        val result = stager.stage(uri)

        assertThat(result).isEqualTo(StagingResult.TooLarge)
        assertThat(stagingDir().listFiles().orEmpty()).isEmpty()
    }

    @Test
    fun `empty content is unreadable, not staged`() {
        val uri = Uri.parse("content://fake/empty.jpg")
        shadowOf(context.contentResolver).registerInputStream(uri, LazyStream(0))

        assertThat(stager.stage(uri)).isEqualTo(StagingResult.Unreadable)
        assertThat(stagingDir().listFiles().orEmpty()).isEmpty()
    }

    @Test
    fun `unopenable uri is unreadable`() {
        assertThat(stager.stage(Uri.parse("file:///nonexistent/nope.jpg")))
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

        val result = stager.stage(Uri.fromFile(source))

        val staged = (result as StagingResult.Staged).attachment
        assertThat(staged.mimeType).isEqualTo("image/jpeg")
        assertThat(staged.sizeBytes).isLessThan(source.length())
        assertThat(staged.file.length()).isEqualTo(staged.sizeBytes)
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
