package app.clearsms.ui.common

import android.content.Context
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import app.clearsms.mms.MmsSizeLimits
import app.clearsms.mms.OutgoingAttachmentStager
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

/**
 * The compose-bar attachment budget: staging copies content in
 * immediately, the running total is enforced against
 * [MmsSizeLimits.TOTAL_BUDGET_BYTES] with an honest inline error, removal
 * cleans staged files up, and consuming hands file ownership to the send.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class ComposerAttachmentsTest {
    private lateinit var context: Context
    private lateinit var stager: OutgoingAttachmentStager

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        stager = OutgoingAttachmentStager(context)
    }

    private fun fileUri(
        name: String,
        bytes: ByteArray,
    ): Uri {
        val file = File(context.cacheDir, name)
        file.writeBytes(bytes)
        return Uri.fromFile(file)
    }

    private fun stagingDir(): File = File(File(context.filesDir, "mms"), "compose")

    @Test
    fun `add stages a copy immediately and tracks its size`() =
        runTest(UnconfinedTestDispatcher()) {
            val composer = ComposerAttachments(stager, this, UnconfinedTestDispatcher(testScheduler))

            composer.add(listOf(fileUri("doc.pdf", ByteArray(2048) { 1 })))

            val staged = composer.attachments.value.single()
            assertThat(staged.sizeBytes).isEqualTo(2048)
            assertThat(staged.displayName).isEqualTo("doc.pdf")
            assertThat(staged.file.exists()).isTrue()
            assertThat(staged.file.parentFile).isEqualTo(stagingDir())
            assertThat(composer.error.value).isNull()
        }

    @Test
    fun `over-budget attachment is refused with an inline error and no staged leftovers`() =
        runTest(UnconfinedTestDispatcher()) {
            val composer = ComposerAttachments(stager, this, UnconfinedTestDispatcher(testScheduler))
            // A non-image is never recompressed, so over-budget stays over-budget.
            val tooBig = ByteArray((MmsSizeLimits.TOTAL_BUDGET_BYTES + 1).toInt())

            composer.add(listOf(fileUri("huge.bin", tooBig)))

            assertThat(composer.attachments.value).isEmpty()
            assertThat(composer.error.value).isEqualTo(AttachmentError.TOO_LARGE)
            assertThat(stagingDir().listFiles().orEmpty()).isEmpty()
        }

    @Test
    fun `second attachment that busts the running total is refused, first survives`() =
        runTest(UnconfinedTestDispatcher()) {
            val composer = ComposerAttachments(stager, this, UnconfinedTestDispatcher(testScheduler))
            val half = ByteArray((MmsSizeLimits.TOTAL_BUDGET_BYTES / 2 + 100).toInt())

            composer.add(listOf(fileUri("a.bin", half), fileUri("b.bin", half)))

            assertThat(composer.attachments.value).hasSize(1)
            assertThat(composer.error.value).isEqualTo(AttachmentError.TOO_LARGE)
        }

    @Test
    fun `unreadable content reports an inline error`() =
        runTest(UnconfinedTestDispatcher()) {
            val composer = ComposerAttachments(stager, this, UnconfinedTestDispatcher(testScheduler))

            composer.add(listOf(Uri.parse("file:///nonexistent/nope.jpg")))

            assertThat(composer.attachments.value).isEmpty()
            assertThat(composer.error.value).isEqualTo(AttachmentError.UNREADABLE)
        }

    @Test
    fun `remove deletes the staged file and clears the error`() =
        runTest(UnconfinedTestDispatcher()) {
            val composer = ComposerAttachments(stager, this, UnconfinedTestDispatcher(testScheduler))
            composer.add(listOf(fileUri("doc.pdf", ByteArray(100))))
            val staged = composer.attachments.value.single()

            composer.remove(staged)

            assertThat(composer.attachments.value).isEmpty()
            assertThat(staged.file.exists()).isFalse()
        }

    @Test
    fun `consume hands the files over without deleting them`() =
        runTest(UnconfinedTestDispatcher()) {
            val composer = ComposerAttachments(stager, this, UnconfinedTestDispatcher(testScheduler))
            composer.add(listOf(fileUri("doc.pdf", ByteArray(100))))

            val consumed = composer.consume()

            assertThat(consumed).hasSize(1)
            assertThat(consumed.single().file.exists()).isTrue()
            assertThat(composer.attachments.value).isEmpty()
        }

    @Test
    fun `discardAll cleans every staged file`() =
        runTest(UnconfinedTestDispatcher()) {
            val composer = ComposerAttachments(stager, this, UnconfinedTestDispatcher(testScheduler))
            composer.add(listOf(fileUri("a.bin", ByteArray(10)), fileUri("b.bin", ByteArray(10))))

            composer.discardAll()

            assertThat(composer.attachments.value).isEmpty()
            assertThat(stagingDir().listFiles().orEmpty()).isEmpty()
        }
}
