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
        assertThat(action).isEqualTo(LaterIntentAction.Navigate(Routes.conversation(42L)))
    }

    @Test
    fun `conversation deep link with messageId carries it into the route`() {
        val action = LaterIntentTriage.classify(view("clearsms://conversation/42?messageId=7"))
        assertThat(action).isEqualTo(LaterIntentAction.Navigate(Routes.conversation(42L, 7L)))
    }

    @Test
    fun `alerts deep link navigates to alerts`() {
        val action = LaterIntentTriage.classify(view("clearsms://alerts"))
        assertThat(action).isEqualTo(LaterIntentAction.Navigate(Routes.ALERTS))
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
