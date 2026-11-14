package app.clearsms.ui.conversation

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import app.clearsms.data.db.AttachmentEntity
import app.clearsms.mms.mmsAttachmentFile
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.ByteArrayOutputStream
import java.io.File

/**
 * Saving an MMS attachment (issue #151): the picker is asked for a document
 * of the attachment's own type and name, the private file is copied - never
 * moved - and the whole thing costs no permission, which the manifest proves.
 */
@RunWith(RobolectricTestRunner::class)
class AttachmentSaveTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    private fun attachment(
        fileName: String,
        mimeType: String = "image/jpeg",
        messageId: Long = 7,
    ) = AttachmentEntity(id = 1, messageId = messageId, mimeType = mimeType, fileName = fileName, sizeBytes = 3)

    @Test
    fun `the suggested name is the human name - stored index prefix stripped`() {
        assertThat(AttachmentSave.suggestedName(attachment("0-photo.jpg"))).isEqualTo("photo.jpg")
        assertThat(AttachmentSave.suggestedName(attachment("12-Scan 2026-11-07.pdf", "application/pdf"))).isEqualTo("Scan 2026-11-07.pdf")
        // A name with its own dash keeps everything after the FIRST one.
        assertThat(AttachmentSave.suggestedName(attachment("1-my-dog.png", "image/png"))).isEqualTo("my-dog.png")
    }

    @Test
    fun `a nameless part is named from its MIME type so it still opens - not just for photos`() {
        assertThat(AttachmentSave.suggestedName(attachment("3-", "audio/amr"))).isEqualTo("attachment.amr")
        assertThat(AttachmentSave.suggestedName(attachment("3-", "application/pdf"))).isEqualTo("attachment.pdf")
        assertThat(AttachmentSave.suggestedName(attachment("3-", "application/x-unknown-thing"))).isEqualTo("attachment.bin")
        assertThat(AttachmentSave.suggestedName(attachment("3-..", "image/png"))).isEqualTo("attachment.png")
    }

    @Test
    fun `the document takes the attachment's real MIME type, with a binary fallback for a blank or wildcard one`() {
        assertThat(AttachmentSave.documentMimeType(attachment("0-a.pdf", "application/pdf"))).isEqualTo("application/pdf")
        assertThat(AttachmentSave.documentMimeType(attachment("0-a.vcf", "text/x-vcard"))).isEqualTo("text/x-vcard")
        assertThat(AttachmentSave.documentMimeType(attachment("0-a", ""))).isEqualTo(AttachmentSave.FALLBACK_MIME)
        assertThat(AttachmentSave.documentMimeType(attachment("0-a", "image/*"))).isEqualTo(AttachmentSave.FALLBACK_MIME)
    }

    @Test
    fun `the contract is ACTION_CREATE_DOCUMENT with the type and proposed title - cancel is null, never an error`() {
        val contract = AttachmentSave.CreateAttachmentDocument()
        val intent = contract.createIntent(context, AttachmentSave.requestFor(attachment("0-voice.amr", "audio/amr")))
        assertThat(intent.action).isEqualTo(Intent.ACTION_CREATE_DOCUMENT)
        assertThat(intent.categories).containsExactly(Intent.CATEGORY_OPENABLE)
        assertThat(intent.type).isEqualTo("audio/amr")
        assertThat(intent.getStringExtra(Intent.EXTRA_TITLE)).isEqualTo("voice.amr")
        // The private path never travels with the intent.
        assertThat(intent.data).isNull()
        assertThat(intent.extras?.keySet()).containsExactly(Intent.EXTRA_TITLE)
        val picked = Uri.parse("content://com.android.externalstorage.documents/document/primary%3ADownload%2Fvoice.amr")
        assertThat(contract.parseResult(Activity.RESULT_OK, Intent().setData(picked))).isEqualTo(picked)
        assertThat(contract.parseResult(Activity.RESULT_CANCELED, null)).isNull()
        assertThat(contract.parseResult(Activity.RESULT_CANCELED, Intent().setData(picked))).isNull()
        assertThat(contract.parseResult(Activity.RESULT_OK, null)).isNull()
    }

    @Test
    fun `copy streams the bytes and leaves the private file where it was`() {
        val source = File.createTempFile("att", ".pdf").apply { writeBytes(byteArrayOf(1, 2, 3, 4, 5)) }
        val sink = ByteArrayOutputStream()
        AttachmentSave.copy(source, sink)
        assertThat(sink.toByteArray()).isEqualTo(byteArrayOf(1, 2, 3, 4, 5))
        assertThat(source.exists()).isTrue()
        assertThat(source.readBytes()).isEqualTo(byteArrayOf(1, 2, 3, 4, 5))
        source.delete()
    }

    @Test
    fun `saveTo copies out of filesDir mms into the document - and reports false when the document cannot be opened`() =
        runBlocking {
            val att = attachment("0-note.txt", "text/plain", messageId = 42)
            val stored =
                mmsAttachmentFile(context.filesDir, 42, att.fileName).apply {
                    parentFile!!.mkdirs()
                    writeText("hello")
                }
            val target = File(context.cacheDir, "picked.txt")
            assertThat(AttachmentSave.saveTo(context, att, Uri.fromFile(target))).isTrue()
            assertThat(target.readText()).isEqualTo("hello")
            assertThat(stored.exists()).isTrue()
            // A document the resolver cannot open is a failure the UI reports, not a crash.
            assertThat(AttachmentSave.saveTo(context, att, Uri.parse("content://nobody.such.provider/doc/1"))).isFalse()
            assertThat(stored.exists()).isTrue()
        }

    @Test
    fun `zero new permissions - the manifest declares no storage permission, before or after`() {
        val manifest = File("src/main/AndroidManifest.xml").readText()
        val declared =
            Regex("<uses-permission(?:-sdk-23)?\\s+android:name=\"([^\"]+)\"")
                .findAll(manifest)
                .map { it.groupValues[1] }
                .toList()
        assertThat(declared.filter { "STORAGE" in it || "MEDIA" in it }).isEmpty()
        assertThat(declared).doesNotContain("android.permission.INTERNET")
        // The save path goes through SAF, never MediaStore.
        val save = File("src/main/kotlin/app/clearsms/ui/conversation/AttachmentSave.kt").readText()
        assertThat(save).contains("Intent.ACTION_CREATE_DOCUMENT")
        // The KDoc may NAME MediaStore (to say why it was rejected); the code never calls it.
        assertThat(save).doesNotContain("MediaStore.")
        assertThat(save).doesNotContain("import android.provider.MediaStore")
        assertThat(save).contains("withContext(Dispatchers.IO)")
    }

    @Test
    fun `save is offered in the viewer and the selection bar, through the one saver and the one snackbar host`() {
        val screen = File("src/main/kotlin/app/clearsms/ui/conversation/ConversationScreen.kt").readText()
        val viewer = File("src/main/kotlin/app/clearsms/ui/conversation/MmsAttachments.kt").readText()
        assertThat(screen).contains("val saveAttachments = rememberAttachmentSaver(snackbarHostState)")
        assertThat(screen).contains("MessageSelectionAction.SAVE_ATTACHMENT -> {")
        assertThat(screen).contains("stringResource(R.string.action_save_attachment)")
        assertThat(screen).contains("hasAttachment = singleItem != null && singleItemAttachments.isNotEmpty(),")
        assertThat(screen).contains("onSave = { saveAttachments(listOf(attachment)) },")
        assertThat(viewer).contains("stringResource(R.string.mms_viewer_save)")
        assertThat(viewer).contains("IconButton(onClick = onSave)")
        // Outcome strings exist; the saver shows them on the host it is handed.
        val saver = File("src/main/kotlin/app/clearsms/ui/conversation/AttachmentSave.kt").readText()
        assertThat(saver).contains("R.string.mms_attachment_saved")
        assertThat(saver).contains("R.string.mms_attachment_save_failed")
        assertThat(saver).contains("snackbarHostState.showSnackbar(")
        // A cancelled picker says nothing: the null branch returns before any snackbar.
        val onNull = saver.substringAfter("if (uri == null || current == null) {").substringBefore("}")
        assertThat(onNull).doesNotContain("showSnackbar")
    }
}
