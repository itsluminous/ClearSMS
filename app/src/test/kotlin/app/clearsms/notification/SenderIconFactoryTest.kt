package app.clearsms.notification

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The notification icon must always render: a generated monogram tile when
 * the sender has no photo, and the same fallback when the photo URI is
 * unreadable — never a crash, never a missing icon.
 */
@RunWith(RobolectricTestRunner::class)
class SenderIconFactoryTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val factory = SenderIconFactory(context)

    @Test
    fun `sender without a photo gets a generated monogram icon`() {
        val icon = factory.iconFor(NotificationSender(name = "HDFC Bank", monogram = "H", colorArgb = 0xFF004C8F.toInt()))
        assertThat(icon).isNotNull()
    }

    @Test
    fun `unreadable photo uri falls back to the monogram icon without crashing`() {
        val icon =
            factory.iconFor(
                NotificationSender(
                    name = "Asha Rao",
                    photoUri = "content://com.android.contacts/display_photo/999999",
                    isContact = true,
                    monogram = "AR",
                ),
            )
        assertThat(icon).isNotNull()
    }

    @Test
    fun `monogram bitmap is a square of the requested size`() {
        val bitmap = SenderIconFactory.monogramBitmap("AB", 0xFF224466.toInt(), sizePx = 64)
        assertThat(bitmap.width).isEqualTo(64)
        assertThat(bitmap.height).isEqualTo(64)
    }

    @Test
    fun `fallback color is deterministic per sender name`() {
        assertThat(SenderIconFactory.fallbackColorFor("Acme"))
            .isEqualTo(SenderIconFactory.fallbackColorFor("Acme"))
    }
}
