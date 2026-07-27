package app.clearsms

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import app.clearsms.ui.navigation.ClearSmsApp
import app.clearsms.work.WorkScheduler
import dagger.hilt.android.AndroidEntryPoint

/**
 * Single-activity, edge-to-edge entry point.
 *
 * Handles ACTION_SEND / ACTION_SENDTO with sms:/smsto:/mms:/mmsto: URIs
 * (default SMS app requirement) by deep-linking into the compose screen.
 */
@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val (recipient, body) = extractSendIntent(intent)
        setContent {
            ClearSmsApp(
                initialRecipient = recipient,
                initialBody = body,
                onOnboarded = { WorkScheduler.scheduleAll(applicationContext) },
            )
        }
    }

    /** Pulls recipient and prefilled body out of a SEND/SENDTO intent, if any. */
    private fun extractSendIntent(intent: Intent?): Pair<String?, String?> {
        if (intent == null) return null to null
        val action = intent.action
        if (action != Intent.ACTION_SEND && action != Intent.ACTION_SENDTO && action != Intent.ACTION_VIEW) {
            return null to null
        }
        val recipient =
            intent.data
                ?.schemeSpecificPart
                ?.substringBefore('?')
                ?.takeIf { it.isNotBlank() }
        val body =
            intent.getStringExtra("sms_body")
                ?: intent.getStringExtra(Intent.EXTRA_TEXT)
        return recipient to body
    }
}
