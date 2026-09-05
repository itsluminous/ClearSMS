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
import java.io.InputStream
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

/** Outcome of staging one picked/shared/captured attachment. */
sealed interface StagingResult {
    /** Copied (and, for images, compressed) into app-private staging. */
    data class Staged(
        val attachment: StagedAttachment,
    ) : StagingResult

    /**
     * Refused up-front: the content exceeds what staging accepts for its
     * type ([OutgoingAttachmentStager.stagingCapBytes]) - either by its
     * declared size before any copy, or mid-copy when the size was
     * undeclared or lied.
     */
    data object TooLarge : StagingResult

    /** The content could not be read (revoked grant, vanished document). */
    data object Unreadable : StagingResult
}

/**
 * App-private staging for compose-bar attachments, under
 * `filesDir/mms/compose/` (covered by the existing FileProvider `mms/`
 * path). Content is copied in IMMEDIATELY on selection because inbound
 * URI grants (share sheet, pickers) die with the receiving activity;
 * images are compressed at staging time so the chips and the running
 * total always show the real payload. The copy STREAMS through a bounded
 * buffer - the picked content is never loaded into memory whole (issue
 * #6: a 377 MB pick OOMed the old `readBytes()` staging) - and content
 * whose size exceeds the per-type cap is refused before any copy when
 * the provider declares its size. Staged files belong to the compose
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
         * returns the outcome: staged, refused as too large, or unreadable.
         */
        fun stage(uri: Uri): StagingResult {
            val id = UUID.randomUUID().toString()
            val raw = File(dir, "$id.stage")
            return try {
                val resolver = context.contentResolver
                val mime = (resolver.getType(uri) ?: guessMime(uri) ?: "application/octet-stream").lowercase()
                val cap = stagingCapBytes(mime)
                // Refuse absurd picks before copying a single byte, when
                // the provider is willing to say how big the content is.
                declaredSize(uri)?.let { size -> if (size > cap) return StagingResult.TooLarge }
                val copied =
                    resolver.openInputStream(uri)?.use { input -> copyBounded(input, raw, cap) }
                        ?: return StagingResult.Unreadable
                when {
                    // The size column was absent or lied; the bounded copy
                    // is the backstop.
                    copied < 0 -> {
                        raw.delete()
                        StagingResult.TooLarge
                    }
                    copied == 0L -> {
                        raw.delete()
                        StagingResult.Unreadable
                    }
                    else -> finish(raw, mime, id) { shrunkMime -> displayNameFor(uri, shrunkMime) }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to stage attachment", e)
                raw.delete()
                StagingResult.Unreadable
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
         * file is deleted. Unreadable when the capture is missing or empty.
         */
        fun stageCameraResult(file: File): StagingResult {
            return try {
                if (!file.exists() || file.length() == 0L) return StagingResult.Unreadable
                finish(file, "image/jpeg", UUID.randomUUID().toString()) { "photo.jpg" }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to stage camera capture", e)
                StagingResult.Unreadable
            } finally {
                file.delete()
            }
        }

        /** Deletes a staged file (chip removed, or compose abandoned). */
        fun discard(attachment: StagedAttachment) {
            attachment.file.delete()
        }

        /**
         * How large a source of [mimeType] may be to enter staging.
         * Recompressible images get [MmsSizeLimits.MAX_STAGED_IMAGE_BYTES]
         * (compression judges pixels, not bytes - see that constant for
         * where the number comes from). Everything else - video included,
         * because the app has NO transcoder (Media3 Transformer is a large,
         * risky dependency; see README's unchecked list) - travels as-is or
         * not at all, so it is capped at the carrier message budget
         * [MmsSizeLimits.TOTAL_BUDGET_BYTES] directly.
         */
        internal fun stagingCapBytes(mimeType: String): Long =
            if (ImageShrink.isCompressible(mimeType)) {
                MmsSizeLimits.MAX_STAGED_IMAGE_BYTES
            } else {
                MmsSizeLimits.TOTAL_BUDGET_BYTES
            }

        /** Compresses (images), names, and moves [raw] into its final staged file. */
        private fun finish(
            raw: File,
            mime: String,
            id: String,
            nameFor: (String) -> String,
        ): StagingResult {
            val shrunk = ImageShrink.shrink(raw, mime, File(dir, "$id.shrunk"))
            val name = nameFor(shrunk.mimeType)
            val final = File(dir, "$id-$name")
            if (!shrunk.file.renameTo(final)) {
                // Same-directory rename should not fail; degrade honestly.
                raw.delete()
                shrunk.file.delete()
                return StagingResult.Unreadable
            }
            if (shrunk.file != raw) raw.delete()
            return StagingResult.Staged(
                StagedAttachment(
                    id = id,
                    file = final,
                    mimeType = shrunk.mimeType,
                    displayName = name,
                    sizeBytes = final.length(),
                ),
            )
        }

        /** The provider-declared content size, or null when unavailable. */
        private fun declaredSize(uri: Uri): Long? =
            try {
                context.contentResolver
                    .query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)
                    ?.use { cursor ->
                        if (cursor.moveToFirst() && !cursor.isNull(0)) {
                            cursor.getLong(0).takeIf { it >= 0 }
                        } else {
                            null
                        }
                    }
            } catch (_: Exception) {
                null
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

/**
 * Streams [input] into [target] through a fixed-size buffer - the whole
 * point of issue #6's fix: memory use is O(buffer), never O(content).
 * Returns the byte count copied, or -1 when the content exceeds
 * [capBytes] (the partial [target] is left for the caller to delete).
 */
internal fun copyBounded(
    input: InputStream,
    target: File,
    capBytes: Long,
): Long {
    target.outputStream().use { out ->
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        var total = 0L
        while (true) {
            val read = input.read(buffer)
            if (read < 0) return total
            total += read
            if (total > capBytes) return -1L
            out.write(buffer, 0, read)
        }
    }
}
