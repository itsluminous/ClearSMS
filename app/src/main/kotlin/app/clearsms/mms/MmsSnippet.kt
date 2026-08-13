package app.clearsms.mms

import app.clearsms.R
import app.clearsms.data.db.MessageEntity
import app.clearsms.data.db.MmsStatus

/**
 * The one place that decides how a message with no displayable body text is
 * labelled - inbox snippets and notification text both use it, so an
 * image-only MMS reads "📷 Photo" everywhere.
 */
object MmsSnippet {
    /**
     * String resource to show INSTEAD of [MessageEntity.body], or null when
     * the body itself is the right snippet (every SMS, and any MMS with a
     * text part).
     */
    fun overrideRes(message: MessageEntity): Int? =
        when {
            message.mmsStatus == MmsStatus.PENDING -> R.string.mms_downloading
            message.mmsStatus == MmsStatus.FAILED -> R.string.mms_download_failed
            message.body.isNotBlank() -> null
            message.attachmentKinds?.contains("IMAGE") == true -> R.string.mms_snippet_photo
            message.attachmentKinds != null -> R.string.mms_snippet_attachment
            else -> null
        }
}
