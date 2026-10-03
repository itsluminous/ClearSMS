package app.clearsms

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.net.toUri

/**
 * THE one way to build an intent that opens a conversation from outside the
 * composition - a notification tap, a launcher shortcut - so every such
 * entry point rides the same mechanism and none can grow its own.
 *
 * The intent is an explicit `ACTION_VIEW clearsms://conversation/<threadId>`
 * (optionally `?messageId=<id>` to scroll to and highlight one message)
 * aimed at [MainActivity] by class name - never implicit, so another app
 * claiming the `clearsms` scheme can never intercept it. [MainActivity]
 * sanitizes it with [IntentTriage.sanitizeDeepLink] and the composition
 * turns it into explicit navigation through `LaterIntentTriage` - on a cold
 * start from the creation intent, on a warm start from `onNewIntent` (the
 * activity is `singleTop`). That triage is where the recorded lesson lives:
 * a tab-targeted link selects its tab with the bottom bar's own nav options
 * instead of a plain push that would corrupt the start destination's saved
 * stack; a conversation link, being no tab, keeps its plain push. A caller
 * here never navigates itself, so it cannot get that wrong.
 *
 * `threadId` is the app's thread identity (anchored on the platform's
 * `thread_id`, issue #42) - the only id the conversation route accepts.
 */
object ConversationDeepLink {
    /** Scheme + host every conversation link starts with; validated by [IntentTriage.isValidDeepLink]. */
    const val PREFIX = "clearsms://conversation/"

    /** The deep-link uri for [threadId], highlighting [messageId] when given. */
    fun uri(
        threadId: Long,
        messageId: Long? = null,
    ): Uri =
        if (messageId != null) {
            "$PREFIX$threadId?messageId=$messageId"
        } else {
            "$PREFIX$threadId"
        }.toUri()

    /**
     * The explicit activity intent for [uri]. NEW_TASK is what a background
     * sender (a notification, the launcher) needs to start the activity;
     * CLEAR_TOP routes the intent to the existing `singleTop` instance's
     * `onNewIntent` instead of stacking a second one.
     */
    fun intent(
        context: Context,
        threadId: Long,
        messageId: Long? = null,
    ): Intent =
        Intent(Intent.ACTION_VIEW, uri(threadId, messageId))
            .setClassName(context, MAIN_ACTIVITY)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)

    /** Class-name string: avoids a compile-time dependency from the notification layer on the UI entry point. */
    const val MAIN_ACTIVITY = "app.clearsms.MainActivity"
}
