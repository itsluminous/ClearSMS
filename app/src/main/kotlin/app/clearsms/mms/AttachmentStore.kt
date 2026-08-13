package app.clearsms.mms

import android.content.Context
import android.webkit.MimeTypeMap
import app.clearsms.data.repository.MmsAttachmentDraft
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * App-private file storage for MMS content under `filesDir/mms/`:
 *
 * - `mms/staging/<messageId>.pdu` - the raw m-retrieve-conf the platform
 *   MMS service writes during a download (deleted after parsing).
 * - `mms/<messageId>/<n>-<name>` - the stored attachment files; metadata
 *   lives in the `attachments` Room table.
 *
 * File names are sanitized (no path separators) and prefixed with the part
 * index so two parts named `image.jpg` can never collide.
 */
@Singleton
class AttachmentStore
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
    ) {
        private val root: File get() = File(context.filesDir, DIR)

        /** The staged PDU file a download for [messageId] writes into. */
        fun stagingFile(messageId: Long): File = File(File(root, STAGING_DIR), "$messageId.pdu").apply { parentFile?.mkdirs() }

        /** Writes [parts] as files for [messageId]; returns their metadata drafts. */
        fun write(
            messageId: Long,
            parts: List<MmsPart>,
        ): List<MmsAttachmentDraft> {
            val dir = File(root, messageId.toString()).apply { mkdirs() }
            return parts.mapIndexed { index, part ->
                val name = fileNameFor(index, part)
                File(dir, name).writeBytes(part.data)
                MmsAttachmentDraft(mimeType = part.mimeType, fileName = name, sizeBytes = part.data.size.toLong())
            }
        }

        /** The stored file behind an attachment row. */
        fun fileFor(
            messageId: Long,
            fileName: String,
        ): File = mmsAttachmentFile(context.filesDir, messageId, fileName)

        /** Removes every stored attachment file (and the directory) of [messageId]. */
        fun deleteFor(messageId: Long) {
            File(root, messageId.toString()).deleteRecursively()
            stagingFile(messageId).delete()
        }

        /**
         * A stable, safe on-disk name: the part index (collision-proof),
         * then the declared name stripped of path separators, or a
         * generated `part.<ext>` from the mime type.
         */
        private fun fileNameFor(
            index: Int,
            part: MmsPart,
        ): String {
            val declared =
                part.fileName
                    ?.substringAfterLast('/')
                    ?.substringAfterLast('\\')
                    ?.trim()
                    ?.takeIf { it.isNotEmpty() && it != "." && it != ".." }
            val base =
                declared ?: run {
                    val ext = MimeTypeMap.getSingleton().getExtensionFromMimeType(part.mimeType) ?: "bin"
                    "part.$ext"
                }
            return "$index-$base"
        }

        private companion object {
            const val DIR = "mms"
            const val STAGING_DIR = "staging"
        }
    }

/**
 * Context-free path resolution for a stored attachment file, usable
 * straight from composables (which have the files dir at hand but no
 * injection point). Must mirror [AttachmentStore]'s layout:
 * `filesDir/mms/<messageId>/<fileName>`.
 */
fun mmsAttachmentFile(
    filesDir: File,
    messageId: Long,
    fileName: String,
): File = File(File(File(filesDir, "mms"), messageId.toString()), fileName)
