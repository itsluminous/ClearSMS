package app.clearsms.ui.navigation

import android.content.Intent
import app.clearsms.IntentTriage
import app.clearsms.domain.model.EnabledSections
import app.clearsms.domain.model.StartDestination

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
        /**
         * True when [route] is a bottom-bar destination: it must be
         * navigated exactly like a bottom-bar tap (popUpTo the graph's
         * start destination with saveState, launchSingleTop, restoreState),
         * never plain-pushed. A plain push lands on whichever tab is
         * currently selected; the next bottom-bar tap then pops it with
         * `saveState = true` - which keys the popped entries as the START
         * destination's saved stack - and `restoreState = true` replays
         * them on every later visit to that tab. That is the "after an
         * Alerts notification, the Inbox tab opens Alerts" regression.
         */
        val selectTab: Boolean,
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
    /**
     * Existing-thread-vs-new-composer: an sms-family URI ALWAYS opens the
     * composer prefilled, never jumps into a matching thread directly.
     * Deliberate: the URI is untrusted third-party input, so resolving it
     * against stored threads here would let a crafted recipient string land
     * a prefilled body inside an unrelated conversation; multi-recipient
     * URIs have no single thread anyway; and nothing is lost - sending from
     * the composer resolves the address to the existing thread (the screen
     * then REPLACES itself with that conversation), so the user ends up in
     * the right thread after the one explicit Send tap, and only then.
     */
    fun classify(intent: Intent): LaterIntentAction {
        deepLinkRoute(intent)?.let {
            return LaterIntentAction.Navigate(it, selectTab = it in Routes.topLevel)
        }
        val send = IntentTriage.extractSendIntent(intent)
        val route =
            if (!send.recipient.isNullOrBlank() || !send.body.isNullOrBlank() || !send.imageUri.isNullOrBlank()) {
                Routes.compose(send.recipient, send.body, send.imageUri)
            } else if (send.explicitCompose) {
                // A bare `sms:` (issue #32): the link explicitly asked for
                // the composer, so it opens EMPTY rather than doing nothing.
                Routes.compose()
            } else {
                null
            }
        if (route != null || send.rejectedAttachment) {
            return LaterIntentAction.OpenCompose(route, send.rejectedAttachment)
        }
        return LaterIntentAction.None
    }

    /**
     * Applies the enabled-sections gate to a classified deep link: a link
     * targeting a bottom-bar TAB whose section is disabled is redirected to
     * the resolved start tab (still as a tab SELECTION - `selectTab` stays
     * true, so the v0.17.2 invariant holds: tab-targeted navigation always
     * uses the bottom bar's own options, never a plain push that would
     * corrupt the start destination's saved state).
     *
     * Redirect - not ignore - because a tapped notification or external
     * link must land the user SOMEWHERE: doing nothing on a warm tap looks
     * broken, and the resolved start is exactly where a cold start would
     * open, so both temperatures agree. This also covers a notification
     * posted BEFORE its section was disabled and tapped after.
     *
     * Non-tab links pass through untouched. In particular a conversation
     * link (with its optional `?messageId=` highlight) stays valid even
     * with Inbox off: the conversation screen is section-independent -
     * Search, Finance and Alerts all open it - so an EXTERNAL intent or a
     * stale pre-disable message notification still shows the exact thread
     * the user asked for instead of being second-guessed.
     */
    fun resolve(
        action: LaterIntentAction.Navigate,
        sections: EnabledSections,
    ): LaterIntentAction.Navigate {
        if (!action.selectTab) return action
        val tab = tabForRoute[action.route] ?: return action
        if (sections.isEnabled(tab)) return action
        return LaterIntentAction.Navigate(route = routeForTab.getValue(sections.resolveStart(tab)), selectTab = true)
    }

    private val routeForTab =
        mapOf(
            StartDestination.INBOX to Routes.INBOX,
            StartDestination.FINANCE to Routes.FINANCE,
            StartDestination.ALERTS to Routes.ALERTS,
        )

    private val tabForRoute = routeForTab.entries.associate { (tab, route) -> route to tab }

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
