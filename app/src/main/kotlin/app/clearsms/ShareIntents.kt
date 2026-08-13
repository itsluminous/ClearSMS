package app.clearsms

import android.content.Intent

/**
 * The ONE way plain text leaves the app via the system share sheet.
 *
 * Used by the notification Share action ([app.clearsms.notification.MessageActionFactory]),
 * the OTP Share action, and the conversation selection bar - a single
 * builder so every share is an identical ACTION_SEND `text/plain` wrapped
 * in a chooser (a chooser ALWAYS: never a silent default-app dispatch).
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
}
