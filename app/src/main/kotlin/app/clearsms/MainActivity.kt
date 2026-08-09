package app.clearsms

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.fragment.app.FragmentActivity
import app.clearsms.ui.finance.BalanceVisibility
import app.clearsms.ui.navigation.ClearSmsApp
import app.clearsms.work.WorkScheduler
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

/**
 * Single-activity, edge-to-edge entry point.
 *
 * Extends [FragmentActivity] (itself a ComponentActivity, so Compose is
 * unaffected) because `androidx.biometric.BiometricPrompt` - the device-lock
 * gate behind Settings → Privacy → Show balance - requires a fragment host.
 *
 * Intent triage (see [IntentTriage]):
 * - ACTION_SEND / ACTION_SENDTO with sms:/smsto:/mms:/mmsto: URIs (default
 *   SMS app requirement) open the compose screen with recipient/body.
 * - ACTION_VIEW `clearsms://` deep links (notification taps) are handled by
 *   the navigation graph only - they must never leak into the compose screen.
 * - Hostile or malformed deep links from third-party apps are stripped before
 *   they reach the navigation controller.
 */
@AndroidEntryPoint
class MainActivity : FragmentActivity() {
    @Inject
    lateinit var balanceVisibility: BalanceVisibility

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // Defensive: any app can fire clearsms:// VIEW intents at this
        // exported activity. Strip malformed deep links (non-numeric or
        // out-of-range ids, unknown hosts) so they can never crash the
        // navigation graph or navigate anywhere unexpected.
        setIntent(IntentTriage.sanitizeDeepLink(intent))
        val send = IntentTriage.extractSendIntent(intent)
        setContent {
            ClearSmsApp(
                initialRecipient = send.recipient,
                initialBody = send.body,
                onOnboarded = { WorkScheduler.scheduleAll(applicationContext) },
            )
        }
    }

    override fun onStop() {
        super.onStop()
        // Reveal-lifetime rule: balances unlocked via the device-lock gate
        // re-mask whenever the app leaves the foreground (recents, home,
        // another app). A rotation is not "leaving", so configuration
        // changes keep the reveal.
        if (!isChangingConfigurations) balanceVisibility.conceal()
    }
}

/** Recipient/body extracted from a compose-style intent; both null otherwise. */
internal data class SendIntent(
    val recipient: String?,
    val body: String?,
)

/**
 * Pure intent-classification helpers for [MainActivity], extracted for
 * testability.
 *
 * Security note: everything arriving here is untrusted - the activity is
 * exported (launcher + default-SMS-app contract), so a co-installed app can
 * send arbitrary actions, schemes, and extras. Nothing in this object may
 * trigger a send or navigation by itself; it only classifies and validates.
 */
internal object IntentTriage {
    private val NONE = SendIntent(null, null)
    private val SMS_SCHEMES = setOf("sms", "smsto", "mms", "mmsto")

    /**
     * Pulls recipient and prefilled body out of a SEND/SENDTO intent, if any.
     *
     * ACTION_VIEW is honored only for the sms-family schemes: `clearsms://`
     * notification deep links (and any other scheme) must be routed by the
     * navigation graph, not the compose screen.
     */
    fun extractSendIntent(intent: Intent?): SendIntent {
        if (intent == null) return NONE
        val isSmsScheme = intent.data?.scheme?.lowercase() in SMS_SCHEMES
        val isCompose =
            when (intent.action) {
                Intent.ACTION_SEND, Intent.ACTION_SENDTO -> true
                Intent.ACTION_VIEW -> isSmsScheme
                else -> false
            }
        if (!isCompose) return NONE
        val recipient =
            intent.data
                ?.takeIf { isSmsScheme }
                ?.schemeSpecificPart
                ?.substringBefore('?')
                ?.takeIf { it.isNotBlank() }
        val body =
            intent.getStringExtra("sms_body")
                ?: intent.getStringExtra(Intent.EXTRA_TEXT)
        return SendIntent(recipient, body)
    }

    /**
     * Returns the intent unchanged unless it is a malformed `clearsms://`
     * deep link, in which case the data (and VIEW action) are dropped so the
     * activity opens on its start destination instead of crashing or
     * mis-navigating.
     */
    fun sanitizeDeepLink(intent: Intent?): Intent? {
        if (intent == null) return null
        val uri = intent.data ?: return intent
        if (!uri.scheme.equals("clearsms", ignoreCase = true)) return intent
        if (intent.action == Intent.ACTION_VIEW && isValidDeepLink(uri)) return intent
        return Intent(intent).setAction(Intent.ACTION_MAIN).setData(null)
    }

    /**
     * Validates the two internal deep-link shapes the app fires from its own
     * notifications: `clearsms://alerts` and
     * `clearsms://conversation/{threadId}[?messageId={id}]` with numeric,
     * non-negative ids. Everything else is rejected.
     */
    fun isValidDeepLink(uri: Uri): Boolean =
        when (uri.host?.lowercase()) {
            "alerts" -> uri.pathSegments.isEmpty()
            "conversation" -> {
                val threadId = uri.pathSegments.singleOrNull()?.toLongOrNull()
                val messageIdParam = runCatching { uri.getQueryParameter("messageId") }.getOrNull()
                val messageIdOk =
                    messageIdParam == null || messageIdParam.toLongOrNull()?.let { it >= -1L } == true
                threadId != null && threadId >= 0L && messageIdOk
            }
            else -> false
        }
}
