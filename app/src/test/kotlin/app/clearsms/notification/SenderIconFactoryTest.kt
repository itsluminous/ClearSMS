package app.clearsms.notification

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import androidx.test.core.app.ApplicationProvider
import app.clearsms.ui.components.AvatarStyle
import app.clearsms.ui.components.BrandCategory
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.concurrent.atomic.AtomicInteger

/**
 * The notification icon must follow the SAME fallback chain as the in-app
 * avatars - contact photo → bundled asset logo → generated brand tile →
 * category tile → letter tile - always render circular, cache per key, and
 * degrade (never crash) on corrupt or missing artwork.
 */
@RunWith(RobolectricTestRunner::class)
@org.robolectric.annotation.GraphicsMode(org.robolectric.annotation.GraphicsMode.Mode.NATIVE)
class SenderIconFactoryTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val factory = SenderIconFactory(context)

    private val hdfc =
        NotificationSender(
            name = "HDFC Bank",
            monogram = "H",
            colorArgb = 0xFF004C8F.toInt(),
            brandKey = "hdfc",
            brandCategory = BrandCategory.BANK,
        )

    @Test
    fun `contact photo wins over a bundled brand logo`() {
        val photoUri = "content://com.android.contacts/display_photo/7"
        shadowOf(context.contentResolver)
            .registerInputStream(android.net.Uri.parse(photoUri), ByteArrayInputStream(pngBytes()))
        val sender = hdfc.copy(photoUri = photoUri, isContact = true)
        assertThat(factory.styleFor(sender)).isEqualTo(AvatarStyle.PHOTO)
        assertThat(factory.largeIconFor(sender)).isNotNull()
    }

    @Test
    fun `known brand with bundled artwork resolves to the asset logo`() {
        assertThat(factory.styleFor(hdfc)).isEqualTo(AvatarStyle.BUNDLED)
        assertThat(factory.largeIconFor(hdfc)).isNotNull()
    }

    @Test
    fun `brand without bundled artwork gets the generated brand tile`() {
        val sender = hdfc.copy(brandKey = "nosuchbrand")
        assertThat(factory.styleFor(sender)).isEqualTo(AvatarStyle.BRAND)
        assertThat(factory.largeIconFor(sender)).isNotNull()
    }

    @Test
    fun `directory-known sender without a brand gets the category tile, unknown gets the letter tile`() {
        val known = NotificationSender(name = "Some Bank", monogram = "SB", isKnownSender = true)
        assertThat(factory.styleFor(known)).isEqualTo(AvatarStyle.BRAND_MARK)
        val unknown = NotificationSender(name = "AX-UNKNWN", monogram = "A")
        assertThat(factory.styleFor(unknown)).isEqualTo(AvatarStyle.PLAIN)
        assertThat(factory.largeIconFor(unknown)).isNotNull()
    }

    @Test
    fun `bundled logo is decoded once per key and the plated bitmap is reused`() {
        val decodes = AtomicInteger()
        val counting =
            SenderIconFactory(context) { key ->
                decodes.incrementAndGet()
                context.assets.open("logos/$key.png").use { android.graphics.BitmapFactory.decodeStream(it) }
            }
        val first = counting.largeIconFor(hdfc)
        val second = counting.largeIconFor(hdfc)
        assertThat(decodes.get()).isEqualTo(1)
        assertThat(second).isSameInstanceAs(first)
    }

    @Test
    fun `generated tile is rendered once per key and reused`() {
        val sender = NotificationSender(name = "AX-UNKNWN", monogram = "A")
        assertThat(factory.largeIconFor(sender)).isSameInstanceAs(factory.largeIconFor(sender))
    }

    @Test
    fun `corrupt logo asset falls back to the generated tile after one attempt`() {
        val attempts = AtomicInteger()
        val corrupt =
            SenderIconFactory(context) {
                attempts.incrementAndGet()
                throw IllegalStateException("corrupt asset")
            }
        assertThat(corrupt.styleFor(hdfc)).isEqualTo(AvatarStyle.BRAND)
        assertThat(corrupt.largeIconFor(hdfc)).isNotNull()
        corrupt.largeIconFor(hdfc)
        assertThat(attempts.get()).isEqualTo(1)
    }

    @Test
    fun `missing logo asset for an unbundled brand key degrades to the tile`() {
        val sender = hdfc.copy(brandKey = "definitely-not-an-asset")
        assertThat(factory.largeIconFor(sender)).isNotNull()
        assertThat(factory.styleFor(sender)).isEqualTo(AvatarStyle.BRAND)
    }

    @Test
    fun `generated tiles render circular - corners transparent, center opaque`() {
        val tile = SenderIconFactory.tileBitmap("H", 0xFF004C8F.toInt(), badge = 'B', sizePx = 128)
        assertThat(Color.alpha(tile.getPixel(1, 1))).isEqualTo(0)
        assertThat(Color.alpha(tile.getPixel(126, 1))).isEqualTo(0)
        assertThat(Color.alpha(tile.getPixel(64, 64))).isEqualTo(255)
    }

    @Test
    fun `bundled logo plates render circular too`() {
        val logo = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.RED) }
        val plate = SenderIconFactory.logoPlate(logo, sizePx = 128)
        assertThat(Color.alpha(plate.getPixel(1, 1))).isEqualTo(0)
        assertThat(Color.alpha(plate.getPixel(64, 64))).isEqualTo(255)
    }

    @Test
    fun `unreadable photo uri falls back to the tile without crashing`() {
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

    private fun pngBytes(): ByteArray {
        val bitmap = Bitmap.createBitmap(32, 32, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.GREEN) }
        return ByteArrayOutputStream()
            .also { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            .toByteArray()
    }
}
