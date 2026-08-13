package app.clearsms

import android.content.Intent
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The single outbound-share builder: every share is an ACTION_SEND
 * `text/plain` wrapped in a chooser, so no share path can silently
 * dispatch to a default app or leak a different mime type.
 */
@RunWith(RobolectricTestRunner::class)
class ShareIntentsTest {
    @Test
    fun `plain text send carries the exact text as EXTRA_TEXT`() {
        val intent = ShareIntents.plainTextSend("hello world")
        assertThat(intent.action).isEqualTo(Intent.ACTION_SEND)
        assertThat(intent.type).isEqualTo("text/plain")
        assertThat(intent.getStringExtra(Intent.EXTRA_TEXT)).isEqualTo("hello world")
    }

    @Test
    fun `chooser wraps the send intent and keeps the payload`() {
        val chooser = ShareIntents.chooser("payload text", "Share message")
        assertThat(chooser.action).isEqualTo(Intent.ACTION_CHOOSER)
        val inner = chooser.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)
        assertThat(inner?.action).isEqualTo(Intent.ACTION_SEND)
        assertThat(inner?.getStringExtra(Intent.EXTRA_TEXT)).isEqualTo("payload text")
        assertThat(chooser.getCharSequenceExtra(Intent.EXTRA_TITLE)).isEqualTo("Share message")
    }

    @Test
    fun `multi-line payload survives unchanged - the multi-select join format`() {
        val joined = "first message\n\nsecond message"
        val intent = ShareIntents.plainTextSend(joined)
        assertThat(intent.getStringExtra(Intent.EXTRA_TEXT)).isEqualTo(joined)
    }
}
