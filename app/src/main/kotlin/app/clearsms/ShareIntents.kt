package app.clearsms

import android.content.Intent
import android.net.Uri

/**
 * The ONE way content leaves the app via the system share sheet.
 *
 * Used by the notification Share action ([app.clearsms.notification.MessageActionFactory]),
 * the OTP Share action, the conversation selection bar, and the MMS
 * attachment viewer - a single builder so every share is an identical
 * ACTION_SEND wrapped in a chooser (a chooser ALWAYS: never a silent
 * default-app dispatch).
 */
object ShareIntents {
    /** Bare ACTION_SEND carrying [text]; the payload the chosen app receives. */
    fun plainTextSend(text: String): Intent =
        Intent(Intent.ACTION_SEND)
            .setType("text/plain")
            .putExtra(Intent.EXTRA_TEXT, text)

    /** [plainTextSend] wrapped in an explicit chooser titled [title]. */
    fun chooser(
        text: String,
        title: CharSequence,
    ): Intent = Intent.createChooser(plainTextSend(text), title)

    /**
     * ACTION_SEND for a stored file (an MMS attachment) behind our
     * FileProvider [uri], with a read-only URI grant for the receiver.
     */
    fun fileSend(
        uri: Uri,
        mimeType: String,
    ): Intent =
        Intent(Intent.ACTION_SEND)
            .setType(mimeType)
            .putExtra(Intent.EXTRA_STREAM, uri)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)

    /** [fileSend] wrapped in an explicit chooser titled [title]. */
    fun fileChooser(
        uri: Uri,
        mimeType: String,
        title: CharSequence,
    ): Intent = Intent.createChooser(fileSend(uri, mimeType), title)
}
