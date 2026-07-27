package app.clearsms

import android.content.Intent
import android.net.Uri
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Intent triage matrix: SEND/SENDTO and sms-family VIEW intents open the
 * compose screen; `clearsms://` deep links (notification taps) must NEVER be
 * treated as compose intents; hostile deep links are stripped before they
 * reach the navigation controller.
 */
@RunWith(RobolectricTestRunner::class)
class IntentTriageTest {
    private fun view(uri: String) = Intent(Intent.ACTION_VIEW, Uri.parse(uri))

    // --- compose-intent shapes -------------------------------------------

    @Test
    fun `smsto view intent yields recipient and body`() {
        val intent = view("smsto:+919876543210?x=1").putExtra("sms_body", "hi")
        val send = IntentTriage.extractSendIntent(intent)
        assertThat(send.recipient).isEqualTo("+919876543210")
        assertThat(send.body).isEqualTo("hi")
    }

    @Test
    fun `sms mms and mmsto schemes are compose intents`() {
        for (scheme in listOf("sms", "mms", "mmsto")) {
            val send = IntentTriage.extractSendIntent(view("$scheme:12345"))
            assertThat(send.recipient).isEqualTo("12345")
        }
    }

    @Test
    fun `sendto action yields recipient`() {
        val intent = Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:12345"))
        assertThat(IntentTriage.extractSendIntent(intent).recipient).isEqualTo("12345")
    }

    @Test
    fun `send action with shared text yields body without recipient`() {
        val intent = Intent(Intent.ACTION_SEND).putExtra(Intent.EXTRA_TEXT, "shared")
        val send = IntentTriage.extractSendIntent(intent)
        assertThat(send.recipient).isNull()
        assertThat(send.body).isEqualTo("shared")
    }

    // --- deep links must never become compose intents --------------------

    @Test
    fun `clearsms conversation deep link is not a compose intent`() {
        val send = IntentTriage.extractSendIntent(view("clearsms://conversation/5?messageId=9"))
        assertThat(send.recipient).isNull()
        assertThat(send.body).isNull()
    }

    @Test
    fun `clearsms alerts deep link is not a compose intent`() {
        val send = IntentTriage.extractSendIntent(view("clearsms://alerts"))
        assertThat(send.recipient).isNull()
        assertThat(send.body).isNull()
    }

    @Test
    fun `plain launcher intent and null intent are not compose intents`() {
        val launcher = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val fromLauncher = IntentTriage.extractSendIntent(launcher)
        assertThat(fromLauncher.recipient).isNull()
        assertThat(fromLauncher.body).isNull()
        val fromNull = IntentTriage.extractSendIntent(null)
        assertThat(fromNull.recipient).isNull()
        assertThat(fromNull.body).isNull()
    }

    @Test
    fun `view intent with foreign scheme carrying text extra is ignored`() {
        // A hostile app must not be able to prefill compose via a random VIEW.
        val intent = view("https://example.com").putExtra("sms_body", "attack")
        assertThat(IntentTriage.extractSendIntent(intent).body).isNull()
    }

    // --- hostile deep-link sanitization -----------------------------------

    @Test
    fun `valid deep links pass sanitization unchanged`() {
        for (uri in listOf(
            "clearsms://alerts",
            "clearsms://conversation/5",
            "clearsms://conversation/5?messageId=42",
        )) {
            val intent = view(uri)
            assertThat(IntentTriage.sanitizeDeepLink(intent)).isSameInstanceAs(intent)
        }
    }

    @Test
    fun `non numeric thread id is stripped`() {
        val out = IntentTriage.sanitizeDeepLink(view("clearsms://conversation/notanumber"))
        assertThat(out!!.data).isNull()
        assertThat(out.action).isEqualTo(Intent.ACTION_MAIN)
    }

    @Test
    fun `overflowing and negative ids are stripped`() {
        for (uri in listOf(
            "clearsms://conversation/99999999999999999999999999",
            "clearsms://conversation/-1",
            "clearsms://conversation/5?messageId=notanumber",
        )) {
            assertThat(IntentTriage.sanitizeDeepLink(view(uri))!!.data).isNull()
        }
    }

    @Test
    fun `unknown hosts and traversal shapes are stripped`() {
        for (uri in listOf(
            "clearsms://evil",
            "clearsms://conversation",
            "clearsms://conversation/5/extra",
            "clearsms://alerts/extra",
        )) {
            assertThat(IntentTriage.sanitizeDeepLink(view(uri))!!.data).isNull()
        }
    }

    @Test
    fun `non clearsms intents are left untouched`() {
        val sms = view("smsto:12345")
        assertThat(IntentTriage.sanitizeDeepLink(sms)).isSameInstanceAs(sms)
        val launcher = Intent(Intent.ACTION_MAIN)
        assertThat(IntentTriage.sanitizeDeepLink(launcher)).isSameInstanceAs(launcher)
    }
}
