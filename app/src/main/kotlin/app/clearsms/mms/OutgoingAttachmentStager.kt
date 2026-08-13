package app.clearsms.mms

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Log
import android.webkit.MimeTypeMap
import androidx.core.content.FileProvider
import app.clearsms.BuildConfig
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * One compose-bar attachment, already copied into app-private staging and
 * already compressed ([ImageShrink]) - [sizeBytes] is what will travel.
 */
data class StagedAttachment(
    /** Stable identity for chip removal (the staged file name is unique). */
    val id: String,
    /** The staged copy under `filesDir/mms/compose/`. */
    val file: File,
    val mimeType: String,
    /** Human name shown on the chip. */
    val displayName: String,
    val sizeBytes: Long,
) {
    val isImage: Boolean get() = mimeType.startsWith("image/")
}

/**
 * App-private staging for compose-bar attachments, under
 * `filesDir/mms/compose/` (covered by the existing FileProvider `mms/`
 * path). Content is copied in IMMEDIATELY on selection because inbound
 * URI grants (share sheet, pickers) die with the receiving activity;
 * images are compressed at staging time so the chips and the running
 * total always show the real payload. Staged files belong to the compose
 * session - sending moves their bytes into the message's attachment
 * directory; removal or abandonment deletes them.
 */
@Singleton
class OutgoingAttachmentStager
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
    ) {
        private val dir: File get() = File(File(context.filesDir, "mms"), "compose").apply { mkdirs() }

        /**
         * Copies [uri]'s content into staging (compressing images) and
         * returns the staged attachment, or null when the content cannot
         * be read (a revoked grant, a vanished document).
         */
        fun stage(uri: Uri): StagedAttachment? {
            return try {
                val resolver = context.contentResolver
                val raw = resolver.openInputStream(uri)?.use { it.readBytes() } ?: return null
                if (raw.isEmpty()) return null
                val declaredMime = resolver.getType(uri) ?: guessMime(uri) ?: "application/octet-stream"
                val shrunk = ImageShrink.shrink(raw, declaredMime.lowercase())
                val name = displayNameFor(uri, shrunk.mimeType)
                write(shrunk.data, shrunk.mimeType, name)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to stage attachment", e)
                null
            }
        }

        /**
         * A fresh camera capture target: the file the camera app writes
         * into (via [cameraUriFor]'s FileProvider grant).
         */
        fun cameraTarget(): File = File(dir, "capture-${UUID.randomUUID()}.jpg")

        /** Grant-able content URI for a [cameraTarget] file. */
        fun cameraUriFor(file: File): Uri = FileProvider.getUriForFile(context, AUTHORITY, file)

        /**
         * Finalizes a completed camera capture: the written JPEG is
         * compressed and re-staged like any picked image; the raw capture
         * file is deleted. Null when the capture is missing or empty.
         */
        fun stageCameraResult(file: File): StagedAttachment? {
            return try {
                if (!file.exists() || file.length() == 0L) return null
                val shrunk = ImageShrink.shrink(file.readBytes(), "image/jpeg")
                write(shrunk.data, shrunk.mimeType, "photo.jpg")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to stage camera capture", e)
                null
            } finally {
                file.delete()
            }
        }

        /** Deletes a staged file (chip removed, or compose abandoned). */
        fun discard(attachment: StagedAttachment) {
            attachment.file.delete()
        }

        private fun write(
            data: ByteArray,
            mimeType: String,
            displayName: String,
        ): StagedAttachment {
            val id = UUID.randomUUID().toString()
            val file = File(dir, "$id-$displayName")
            file.writeBytes(data)
            return StagedAttachment(
                id = id,
                file = file,
                mimeType = mimeType,
                displayName = displayName,
                sizeBytes = data.size.toLong(),
            )
        }

        /** A safe display/file name: the document's own, or a typed default. */
        private fun displayNameFor(
            uri: Uri,
            mimeType: String,
        ): String {
            val declared =
                try {
                    context.contentResolver
                        .query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                        ?.use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }
                } catch (_: Exception) {
                    null
                } ?: uri.lastPathSegment
            val sanitized =
                declared
                    ?.substringAfterLast('/')
                    ?.substringAfterLast('\\')
                    ?.trim()
                    ?.takeIf { it.isNotEmpty() && it != "." && it != ".." }
            if (sanitized != null) return sanitized
            val ext = MimeTypeMap.getSingleton().getExtensionFromMimeType(mimeType) ?: "bin"
            return "attachment.$ext"
        }

        private fun guessMime(uri: Uri): String? =
            uri.lastPathSegment
                ?.substringAfterLast('.', missingDelimiterValue = "")
                ?.takeIf { it.isNotEmpty() }
                ?.let { MimeTypeMap.getSingleton().getMimeTypeFromExtension(it.lowercase()) }

        private companion object {
            const val TAG = "OutgoingAttachmentStager"
            const val AUTHORITY = BuildConfig.APPLICATION_ID + ".fileprovider"
        }
    }
