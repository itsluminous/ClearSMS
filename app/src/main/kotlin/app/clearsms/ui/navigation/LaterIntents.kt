package app.clearsms.ui.navigation

import android.content.Intent
import app.clearsms.IntentTriage

/**
 * What an intent that arrived AFTER the activity was created should do to a
 * live navigation graph.
 *
 * With `singleTop` a notification tap or a share into a running app is
 * delivered to `MainActivity.onNewIntent`, not `onCreate` - and the
 * NavController only resolves deep links from the intent it was constructed
 * with. These intents are therefore relayed into the composition and turned
 * into explicit navigation by [LaterIntentTriage.classify].
 */
internal sealed interface LaterIntentAction {
    /** A valid `clearsms://` deep link: navigate straight to [route]. */
    data class Navigate(
        val route: String,
    ) : LaterIntentAction

    /**
     * A SEND/SENDTO-family compose intent. [route] is null when the share
     * carried nothing usable (e.g. only a rejected non-image attachment);
     * [rejectedAttachment] still deserves its toast in that case.
     */
    data class OpenCompose(
        val route: String?,
        val rejectedAttachment: Boolean,
    ) : LaterIntentAction

    /** Nothing to do (launcher relaunch, sanitized junk, unknown intent). */
    data object None : LaterIntentAction
}

/**
 * Pure intent -> destination mapping for post-launch intents, extracted for
 * unit testability (this regression - GitHub issue #8 - was invisible in
 * code review precisely because nothing exercised the "app already running"
 * path).
 *
 * Reuses [IntentTriage] for validation, so hostile deep links and non-image
 * attachments are rejected identically to the `onCreate` path.
 */
internal object LaterIntentTriage {
    fun classify(intent: Intent): LaterIntentAction {
        deepLinkRoute(intent)?.let { return LaterIntentAction.Navigate(it) }
        val send = IntentTriage.extractSendIntent(intent)
        val route =
            if (!send.recipient.isNullOrBlank() || !send.body.isNullOrBlank() || !send.imageUri.isNullOrBlank()) {
                Routes.compose(send.recipient, send.body, send.imageUri)
            } else {
                null
            }
        if (route != null || send.rejectedAttachment) {
            return LaterIntentAction.OpenCompose(route, send.rejectedAttachment)
        }
        return LaterIntentAction.None
    }

    /**
     * The graph route for a valid `clearsms://` VIEW intent, or null. Mirrors
     * the `navDeepLink` patterns declared on the graph: `clearsms://alerts`
     * and `clearsms://conversation/{threadId}[?messageId={id}]`.
     */
    fun deepLinkRoute(intent: Intent): String? {
        if (intent.action != Intent.ACTION_VIEW) return null
        val uri = intent.data ?: return null
        if (!uri.scheme.equals("clearsms", ignoreCase = true)) return null
        if (!IntentTriage.isValidDeepLink(uri)) return null
        return when (uri.host?.lowercase()) {
            "alerts" -> Routes.ALERTS
            "conversation" -> {
                val threadId = uri.pathSegments.singleOrNull()?.toLongOrNull() ?: return null
                val messageId = uri.getQueryParameter("messageId")?.toLongOrNull() ?: -1L
                Routes.conversation(threadId, messageId)
            }
            else -> null
        }
    }
}
