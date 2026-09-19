package app.clearsms.ui.navigation

import android.content.Intent
import android.net.Uri
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Intent -> destination mapping for intents that arrive while the app is
 * already running (GitHub issue #8: a notification tap did nothing because
 * nothing consumed the new intent). These tests pin the mapping the live
 * graph applies in `MainScaffold`'s `laterIntents` collector.
 */
@RunWith(RobolectricTestRunner::class)
class LaterIntentTriageTest {
    private fun view(uri: String) = Intent(Intent.ACTION_VIEW, Uri.parse(uri))

    // --- notification deep links -----------------------------------------

    @Test
    fun `conversation deep link navigates to the conversation route`() {
        val action = LaterIntentTriage.classify(view("clearsms://conversation/42"))
        assertThat(action).isEqualTo(LaterIntentAction.Navigate(Routes.conversation(42L), selectTab = false))
    }

    @Test
    fun `conversation deep link with messageId carries it into the route`() {
        val action = LaterIntentTriage.classify(view("clearsms://conversation/42?messageId=7"))
        assertThat(action).isEqualTo(LaterIntentAction.Navigate(Routes.conversation(42L, 7L), selectTab = false))
    }

    @Test
    fun `alerts deep link navigates to alerts`() {
        val action = LaterIntentTriage.classify(view("clearsms://alerts"))
        assertThat(action).isEqualTo(LaterIntentAction.Navigate(Routes.ALERTS, selectTab = true))
    }

    // --- tab-targeted deep links must SELECT the tab, never plain-push ----

    /**
     * Regression: a bill-due notification deep-links to `clearsms://alerts`,
     * a bottom-bar destination. If that route is plain-pushed onto the
     * currently selected tab's stack, the next bottom-bar tap pops it with
     * `saveState = true` - keying it as the START destination's saved
     * stack - and `restoreState = true` then replays it on every visit:
     * "after the notification, the Inbox tab opens Alerts", persistently.
     */
    @Test
    fun `deep link to a top-level tab demands tab-selecting navigation`() {
        for (uri in listOf("clearsms://alerts", "clearsms://ALERTS")) {
            val action = LaterIntentTriage.classify(view(uri))
            assertThat(action).isInstanceOf(LaterIntentAction.Navigate::class.java)
            assertThat((action as LaterIntentAction.Navigate).selectTab).isTrue()
        }
    }

    @Test
    fun `deep link to a conversation stays a plain push`() {
        val action = LaterIntentTriage.classify(view("clearsms://conversation/42?messageId=7"))
        assertThat((action as LaterIntentAction.Navigate).selectTab).isFalse()
    }

    // --- hostile or malformed deep links do nothing -----------------------

    @Test
    fun `malformed and hostile deep links are ignored`() {
        for (uri in listOf(
            "clearsms://conversation/notanumber",
            "clearsms://conversation/-1",
            "clearsms://conversation/1/extra",
            "clearsms://settings",
            "clearsms://conversation/1?messageId=abc",
        )) {
            assertThat(LaterIntentTriage.classify(view(uri))).isEqualTo(LaterIntentAction.None)
        }
    }

    @Test
    fun `deep link without VIEW action is ignored`() {
        val intent = Intent(Intent.ACTION_MAIN).setData(Uri.parse("clearsms://conversation/1"))
        assertThat(LaterIntentTriage.classify(intent)).isEqualTo(LaterIntentAction.None)
    }

    @Test
    fun `launcher relaunch intent does nothing`() {
        assertThat(LaterIntentTriage.classify(Intent(Intent.ACTION_MAIN))).isEqualTo(LaterIntentAction.None)
    }

    // --- shares into a running app (singleTop routes them here too) -------

    @Test
    fun `send intent with text opens compose prefilled`() {
        val intent = Intent(Intent.ACTION_SEND).putExtra(Intent.EXTRA_TEXT, "shared text")
        val action = LaterIntentTriage.classify(intent)
        assertThat(action).isEqualTo(
            LaterIntentAction.OpenCompose(Routes.compose(body = "shared text"), rejectedAttachment = false),
        )
    }

    @Test
    fun `sendto intent opens compose with recipient`() {
        val intent = Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:12345"))
        val action = LaterIntentTriage.classify(intent)
        assertThat(action).isEqualTo(
            LaterIntentAction.OpenCompose(Routes.compose(recipient = "12345"), rejectedAttachment = false),
        )
    }

    // --- sms-link VIEW intents into a running app (issue #32) -------------

    @Test
    fun `view sms uri with body opens compose prefilled for every scheme`() {
        for (scheme in listOf("sms", "smsto", "mms", "mmsto")) {
            val action = LaterIntentTriage.classify(view("$scheme:%2B15551234?body=hi%20there"))
            assertThat(action).isEqualTo(
                LaterIntentAction.OpenCompose(
                    Routes.compose(recipient = "+15551234", body = "hi there"),
                    rejectedAttachment = false,
                ),
            )
        }
    }

    @Test
    fun `view sms uri with multiple recipients carries all of them`() {
        val action = LaterIntentTriage.classify(view("sms:12345;67890?body=team"))
        assertThat(action).isEqualTo(
            LaterIntentAction.OpenCompose(
                Routes.compose(recipient = "12345,67890", body = "team"),
                rejectedAttachment = false,
            ),
        )
    }

    @Test
    fun `bare sms uri opens an EMPTY composer instead of doing nothing`() {
        val action = LaterIntentTriage.classify(view("sms:"))
        assertThat(action).isEqualTo(LaterIntentAction.OpenCompose(Routes.compose(), rejectedAttachment = false))
    }

    @Test
    fun `malformed sms uri never throws and never navigates a deep link`() {
        for (uri in listOf("sms:%GG?body=%ZZ", "sms:,,;;", "sms:?????")) {
            val action = LaterIntentTriage.classify(view(uri)) // must not throw
            // Worst case it opens a composer with inert prefill - it must
            // never become a Navigate into an existing thread.
            assertThat(action).isNotInstanceOf(LaterIntentAction.Navigate::class.java)
        }
    }

    @Test
    fun `sms uri never resolves into an existing conversation - composer only`() {
        // Existing-thread-vs-composer decision: untrusted URIs prefill the
        // composer; the SEND tap (and only it) lands in the matching thread.
        val action = LaterIntentTriage.classify(view("smsto:12345?body=pay%20me"))
        assertThat(action).isInstanceOf(LaterIntentAction.OpenCompose::class.java)
        assertThat((action as LaterIntentAction.OpenCompose).route).startsWith("compose?")
    }

    @Test
    fun `image share opens compose with the image uri`() {
        val intent =
            Intent(Intent.ACTION_SEND)
                .setType("image/png")
                .putExtra(Intent.EXTRA_STREAM, Uri.parse("content://media/1"))
        val action = LaterIntentTriage.classify(intent)
        assertThat(action).isEqualTo(
            LaterIntentAction.OpenCompose(
                Routes.compose(imageUri = "content://media/1"),
                rejectedAttachment = false,
            ),
        )
    }

    @Test
    fun `non-image share keeps the toast but has nowhere to navigate`() {
        val intent =
            Intent(Intent.ACTION_SEND)
                .setType("video/mp4")
                .putExtra(Intent.EXTRA_STREAM, Uri.parse("content://media/2"))
        val action = LaterIntentTriage.classify(intent)
        assertThat(action).isEqualTo(LaterIntentAction.OpenCompose(route = null, rejectedAttachment = true))
    }
}
