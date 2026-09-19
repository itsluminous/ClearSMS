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

    // --- standard sms-link URI shapes (issue #32) -------------------------

    @Test
    fun `all four schemes mark the intent as explicit compose`() {
        for (scheme in listOf("sms", "smsto", "mms", "mmsto")) {
            val send = IntentTriage.extractSendIntent(view("$scheme:+15551234"))
            assertThat(send.recipient).isEqualTo("+15551234")
            assertThat(send.explicitCompose).isTrue()
        }
    }

    @Test
    fun `body query parameter is decoded - spaces newlines and non-ascii`() {
        val send =
            IntentTriage.extractSendIntent(
                view("sms:+15551234?body=Hello%20world%0Anext%20line%20%E0%A4%A8%E0%A4%AE%E0%A4%B8%E0%A5%8D%E0%A4%A4%E0%A5%87"),
            )
        assertThat(send.recipient).isEqualTo("+15551234")
        assertThat(send.body).isEqualTo("Hello world\nnext line नमस्ते")
    }

    @Test
    fun `encoded ampersand inside the body survives - the classic parsing trap`() {
        // Decoding before splitting the query would turn %26 into a bare &
        // and truncate the body at "you".
        val send = IntentTriage.extractSendIntent(view("sms:12345?body=you%20%26%20me"))
        assertThat(send.body).isEqualTo("you & me")
    }

    @Test
    fun `literal plus in the body stays a plus - the sms scheme is not form-encoded`() {
        // RFC 5724 mandates %20 for spaces; a "+" is a real character.
        val send = IntentTriage.extractSendIntent(view("sms:12345?body=1%2B1=2+ok"))
        assertThat(send.body).isEqualTo("1+1=2+ok")
    }

    @Test
    fun `multiple recipients - comma and semicolon separated both normalize to commas`() {
        for (uri in listOf("smsto:12345,67890", "smsto:12345;67890", "sms:12345,%2B15551234;67890")) {
            val send = IntentTriage.extractSendIntent(view(uri))
            assertThat(send.recipient).contains("12345")
            assertThat(send.recipient).doesNotContain(";")
        }
        val mixed = IntentTriage.extractSendIntent(view("sms:12345,%2B15551234;67890"))
        assertThat(mixed.recipient).isEqualTo("12345,+15551234,67890")
    }

    @Test
    fun `percent-encoded plus in the recipient decodes`() {
        val send = IntentTriage.extractSendIntent(view("smsto:%2B15551234?body=hi"))
        assertThat(send.recipient).isEqualTo("+15551234")
        assertThat(send.body).isEqualTo("hi")
    }

    @Test
    fun `scheme with no recipient still asks for the composer - empty not failing`() {
        for (uri in listOf("sms:", "smsto:", "mms:", "mmsto:")) {
            val send = IntentTriage.extractSendIntent(view(uri))
            assertThat(send.recipient).isNull()
            assertThat(send.body).isNull()
            assertThat(send.explicitCompose).isTrue()
        }
    }

    @Test
    fun `body without recipient prefills only the body`() {
        val send = IntentTriage.extractSendIntent(view("sms:?body=just%20text"))
        assertThat(send.recipient).isNull()
        assertThat(send.body).isEqualTo("just text")
    }

    @Test
    fun `sms_body extra outranks the uri body`() {
        val send = IntentTriage.extractSendIntent(view("smsto:12345?body=from-uri").putExtra("sms_body", "from-extra"))
        assertThat(send.body).isEqualTo("from-extra")
    }

    @Test
    fun `malformed sms uris never throw and never invent content`() {
        for (uri in listOf(
            "sms:%GG?body=%ZZ", // broken percent escapes
            "sms://12345?body=hi", // non-standard hierarchical form
            "sms:?????", // separator soup
            "sms:,,;;", // only separators - no usable recipient
            "sms:?body=", // empty body value
            "sms:?notbody=x&", // irrelevant params, trailing separator
            "sms:12345?body", // body with no '='
        )) {
            val send = IntentTriage.extractSendIntent(view(uri)) // must not throw
            // Whatever survives is inert prefill text: no crash, no send.
            assertThat(send.rejectedAttachment).isFalse()
        }
        // The hierarchical form still finds its recipient and body.
        val hier = IntentTriage.extractSendIntent(view("sms://12345?body=hi"))
        assertThat(hier.recipient).isEqualTo("12345")
        assertThat(hier.body).isEqualTo("hi")
        // Separator-only recipients collapse to none.
        assertThat(IntentTriage.extractSendIntent(view("sms:,,;;")).recipient).isNull()
        // An empty body value stays null.
        assertThat(IntentTriage.extractSendIntent(view("sms:?body=")).body).isNull()
    }

    @Test
    fun `plain text share is not explicit compose`() {
        val intent = Intent(Intent.ACTION_SEND).putExtra(Intent.EXTRA_TEXT, "shared")
        assertThat(IntentTriage.extractSendIntent(intent).explicitCompose).isFalse()
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

    // --- inbound media shares --------------------------------------------

    @Test
    fun `image share carries the stream uri and any text - never a recipient`() {
        val intent =
            Intent(Intent.ACTION_SEND)
                .setType("image/jpeg")
                .putExtra(Intent.EXTRA_STREAM, Uri.parse("content://media/external/images/1"))
                .putExtra(Intent.EXTRA_TEXT, "look at this")
        val send = IntentTriage.extractSendIntent(intent)
        assertThat(send.imageUri).isEqualTo("content://media/external/images/1")
        assertThat(send.body).isEqualTo("look at this")
        assertThat(send.recipient).isNull()
        assertThat(send.rejectedAttachment).isFalse()
    }

    @Test
    fun `non-image share stream is rejected politely - text still honored`() {
        val intent =
            Intent(Intent.ACTION_SEND)
                .setType("video/mp4")
                .putExtra(Intent.EXTRA_STREAM, Uri.parse("content://media/external/video/1"))
                .putExtra(Intent.EXTRA_TEXT, "watch this")
        val send = IntentTriage.extractSendIntent(intent)
        assertThat(send.imageUri).isNull()
        assertThat(send.rejectedAttachment).isTrue()
        assertThat(send.body).isEqualTo("watch this")
    }

    @Test
    fun `plain text share has no image and no rejection`() {
        val intent = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, "hello")
        val send = IntentTriage.extractSendIntent(intent)
        assertThat(send.imageUri).isNull()
        assertThat(send.rejectedAttachment).isFalse()
        assertThat(send.body).isEqualTo("hello")
    }
}
