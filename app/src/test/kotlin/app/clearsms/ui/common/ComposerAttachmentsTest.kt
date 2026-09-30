package app.clearsms.ui.common

import android.content.Context
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import app.clearsms.mms.FakeCarrierMmsLimits
import app.clearsms.mms.MmsSizeBudget
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
 * immediately, the running total is enforced against the chosen SIM's
 * CARRIER limit ([MmsSizeBudget]) with an honest inline error that names
 * it, removal cleans staged files up, and consuming hands file ownership
 * to the send.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class ComposerAttachmentsTest {
    private lateinit var context: Context
    private lateinit var carrier: FakeCarrierMmsLimits
    private lateinit var stager: OutgoingAttachmentStager

    /** The reporter's carrier: 300 KiB, so the attachment target is 299 008 bytes. */
    private val target = MmsSizeBudget.forCarrier(307_200).attachmentTargetBytes

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        carrier = FakeCarrierMmsLimits(307_200)
        stager = OutgoingAttachmentStager(context, carrier)
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
    fun `over-budget attachment is refused with an inline error naming the carrier limit, and no staged leftovers`() =
        runTest(UnconfinedTestDispatcher()) {
            val composer = ComposerAttachments(stager, this, UnconfinedTestDispatcher(testScheduler))
            // A non-image is never recompressed, so over-budget stays over-budget.
            val tooBig = ByteArray((target + 1).toInt())

            composer.add(listOf(fileUri("huge.bin", tooBig)))

            assertThat(composer.attachments.value).isEmpty()
            assertThat(composer.error.value).isEqualTo(AttachmentError.TooLarge(limitBytes = 307_200L))
            assertThat(stagingDir().listFiles().orEmpty()).isEmpty()
            // The size line shows the budget the refusal was judged against.
            assertThat(composer.budgetBytes.value).isEqualTo(target)
        }

    @Test
    fun `second attachment that busts the running total is refused, first survives`() =
        runTest(UnconfinedTestDispatcher()) {
            val composer = ComposerAttachments(stager, this, UnconfinedTestDispatcher(testScheduler))
            val half = ByteArray((target / 2 + 100).toInt())

            composer.add(listOf(fileUri("a.bin", half), fileUri("b.bin", half)))

            assertThat(composer.attachments.value).hasSize(1)
            assertThat(composer.error.value).isEqualTo(AttachmentError.TooLarge(limitBytes = 307_200L))
        }

    @Test
    fun `the budget is the CHOSEN SIM's carrier limit - a generous carrier takes what a strict one refuses`() =
        runTest(UnconfinedTestDispatcher()) {
            var sim: Int? = 3
            val composer = ComposerAttachments(stager, this, UnconfinedTestDispatcher(testScheduler)) { sim }
            val bytes = ByteArray(500_000)

            composer.add(listOf(fileUri("strict.bin", bytes)))
            assertThat(composer.error.value).isEqualTo(AttachmentError.TooLarge(limitBytes = 307_200L))
            assertThat(carrier.asked).containsExactly(3)

            // The user cycles to the other SIM, whose carrier allows 1 MiB.
            sim = 7
            carrier.maxMessageSizeBytes = 1_048_576
            composer.refreshBudget()
            assertThat(composer.budgetBytes.value).isEqualTo(1_048_576L - 8_192L)
            composer.add(listOf(fileUri("generous.bin", bytes)))

            assertThat(
                composer.attachments.value
                    .single()
                    .sizeBytes,
            ).isEqualTo(500_000L)
            assertThat(composer.error.value).isNull()
            assertThat(carrier.asked).contains(7)
        }

    @Test
    fun `an unknown carrier limit is budgeted at the AOSP default, not the old 1 MB`() =
        runTest(UnconfinedTestDispatcher()) {
            carrier.maxMessageSizeBytes = null
            val composer = ComposerAttachments(stager, this, UnconfinedTestDispatcher(testScheduler))

            composer.add(listOf(fileUri("halfmeg.bin", ByteArray(500_000))))

            assertThat(composer.attachments.value).isEmpty()
            assertThat(composer.error.value).isEqualTo(AttachmentError.TooLarge(limitBytes = 307_200L))
            assertThat(composer.budgetBytes.value).isEqualTo(299_008L)
        }

    @Test
    fun `unreadable content reports an inline error`() =
        runTest(UnconfinedTestDispatcher()) {
            val composer = ComposerAttachments(stager, this, UnconfinedTestDispatcher(testScheduler))

            composer.add(listOf(Uri.parse("file:///nonexistent/nope.jpg")))

            assertThat(composer.attachments.value).isEmpty()
            assertThat(composer.error.value).isEqualTo(AttachmentError.Unreadable)
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
