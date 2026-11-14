package app.clearsms.ui.conversation

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.webkit.MimeTypeMap
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContract
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import app.clearsms.R
import app.clearsms.data.db.AttachmentEntity
import app.clearsms.mms.mmsAttachmentFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.io.OutputStream

/**
 * Saving an MMS attachment out of the app (issue #151) through the Storage
 * Access Framework: `ACTION_CREATE_DOCUMENT` lets the user pick the folder
 * and costs NO permission on any supported API level (23-35) - the same
 * path every other file leaves this app by (backups, rules). MediaStore
 * was rejected: on API 23-28 it needs WRITE_EXTERNAL_STORAGE, which would
 * have to be declared for EVERY install (minSdk 23) and would break the
 * README's permission promise for a save that SAF does for free.
 *
 * The attachment is COPIED out of `filesDir/mms` into the document the
 * picker created; the private file is never moved and its path never
 * leaves the app. The picker names the file after the attachment's own
 * name and the document carries its real MIME type, so a PDF or an audio
 * clip saves as faithfully as a photo. Collisions are the picker's job:
 * the provider that owns the folder renames or asks, as it does for any app.
 */
object AttachmentSave {
    /** The MIME type a document is created with when the attachment declares none. */
    const val FALLBACK_MIME = "application/octet-stream"

    /**
     * The name offered in the picker: the attachment's human name - the
     * stored name minus its collision-proof index prefix ("0-photo.jpg" ->
     * "photo.jpg") - or, for a part that arrived nameless, `attachment.<ext>`
     * from the MIME type so the file still opens in the right app.
     */
    fun suggestedName(attachment: AttachmentEntity): String {
        val human = attachment.fileName.substringAfter('-', missingDelimiterValue = attachment.fileName).trim()
        if (human.isNotEmpty() && human != "." && human != "..") return human
        val ext = MimeTypeMap.getSingleton().getExtensionFromMimeType(attachment.mimeType) ?: "bin"
        return "attachment.$ext"
    }

    /** The MIME type the document is created with; never blank, never a wildcard. */
    fun documentMimeType(attachment: AttachmentEntity): String =
        attachment.mimeType.trim().takeIf { it.isNotEmpty() && !it.contains('*') } ?: FALLBACK_MIME

    /** What the picker is asked for: one document, with this type and this name. */
    fun requestFor(attachment: AttachmentEntity): Request =
        Request(mimeType = documentMimeType(attachment), suggestedName = suggestedName(attachment))

    /** Streams [source] into [sink]. The source is read only, never moved or deleted. */
    @Throws(IOException::class)
    fun copy(
        source: File,
        sink: OutputStream,
    ) {
        source.inputStream().use { input -> sink.use { out -> input.copyTo(out) } }
    }

    /**
     * Copies the stored file behind [attachment] into [target] on the IO
     * dispatcher. False when the document could not be opened or written -
     * the caller reports that; a cancelled picker never reaches here.
     */
    suspend fun saveTo(
        context: Context,
        attachment: AttachmentEntity,
        target: Uri,
    ): Boolean =
        withContext(Dispatchers.IO) {
            val source = mmsAttachmentFile(context.filesDir, attachment.messageId, attachment.fileName)
            try {
                val sink = context.contentResolver.openOutputStream(target, "wt") ?: return@withContext false
                copy(source, sink)
                true
            } catch (_: IOException) {
                false
            } catch (_: SecurityException) {
                false
            }
        }

    /** Input of [CreateAttachmentDocument]: the created document's type and proposed name. */
    data class Request(
        val mimeType: String,
        val suggestedName: String,
    )

    /**
     * `ActivityResultContracts.CreateDocument` fixes one MIME type at
     * construction; attachments each carry their own, so this contract
     * takes the type with the request. Same intent otherwise: openable
     * document, [Intent.EXTRA_TITLE] as the proposed name, the created
     * document's uri as the result, null when the user backed out.
     */
    class CreateAttachmentDocument : ActivityResultContract<Request, Uri?>() {
        override fun createIntent(
            context: Context,
            input: Request,
        ): Intent =
            Intent(Intent.ACTION_CREATE_DOCUMENT)
                .addCategory(Intent.CATEGORY_OPENABLE)
                .setType(input.mimeType)
                .putExtra(Intent.EXTRA_TITLE, input.suggestedName)

        override fun parseResult(
            resultCode: Int,
            intent: Intent?,
        ): Uri? = intent?.data?.takeIf { resultCode == Activity.RESULT_OK }
    }
}

/**
 * The one save path both surfaces (the full-screen viewer and the selection
 * bar) call: launches the SAF picker for the first attachment, copies into
 * the chosen document off the main thread, then moves on to the next one,
 * so a message with several parts saves them one picker at a time. The
 * outcome goes to [snackbarHostState] - the screen's single host - as
 * "Saved" or "Could not save". Backing out of the picker is not an error:
 * it says nothing and drops whatever was still queued, since the user just
 * declined to pick a place.
 */
@Composable
internal fun rememberAttachmentSaver(snackbarHostState: SnackbarHostState): (List<AttachmentEntity>) -> Unit {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val savedMessage = stringResource(R.string.mms_attachment_saved)
    val failedMessage = stringResource(R.string.mms_attachment_save_failed)
    // The queue outlives the picker round trip: head is the attachment the
    // open picker is for, the rest wait for their own picker.
    var queue by remember { mutableStateOf<List<AttachmentEntity>>(emptyList()) }
    var launchNext: (() -> Unit)? by remember { mutableStateOf(null) }
    val launcher =
        rememberLauncherForActivityResult(AttachmentSave.CreateAttachmentDocument()) { uri ->
            val current = queue.firstOrNull()
            if (uri == null || current == null) {
                // Cancelled: silent, and the rest of the queue is abandoned.
                queue = emptyList()
                return@rememberLauncherForActivityResult
            }
            queue = queue.drop(1)
            scope.launch {
                val saved = AttachmentSave.saveTo(context, current, uri)
                if (queue.isNotEmpty()) launchNext?.invoke()
                snackbarHostState.showSnackbar(if (saved) savedMessage else failedMessage)
            }
        }
    launchNext = { queue.firstOrNull()?.let { launcher.launch(AttachmentSave.requestFor(it)) } }
    return { attachments ->
        if (attachments.isNotEmpty() && queue.isEmpty()) {
            queue = attachments
            launcher.launch(AttachmentSave.requestFor(attachments.first()))
        }
    }
}
