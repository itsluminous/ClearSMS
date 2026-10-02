package app.clearsms.shortcuts

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import androidx.core.graphics.ColorUtils
import androidx.test.core.app.ApplicationProvider
import app.clearsms.notification.NotificationSender
import app.clearsms.notification.SenderIconFactory
import app.clearsms.ui.components.AvatarStyle
import app.clearsms.ui.components.initialsOf
import app.clearsms.ui.components.plainAvatarColorArgb
import app.clearsms.ui.components.plainAvatarHue
import app.clearsms.ui.components.plainAvatarInitial
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

/**
 * A conversation shortcut must read as the SAME conversation as its inbox
 * row. Device verification caught the letter avatar diverging: the inbox
 * drew "A" on the `AVATAR_HUES` pastel while the shortcut (and the
 * notification) drew the brand-mark style "AR" on another hue wheel - two
 * letters and two colours for one contact, side by side on the launcher.
 *
 * These tests pin the shortcut's letter tile to the inbox's own derivation
 * (`PlainAvatar` → [plainAvatarInitial] / [plainAvatarColorArgb]) down to
 * the rendered pixel, so the two cannot silently drift apart again - and
 * that the contact-photo and brand-mark tiers, which already matched, stay
 * as they are.
 */
@RunWith(RobolectricTestRunner::class)
class ShortcutAvatarParityTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val factory = SenderIconFactory(context)

    /** A saved contact without a photo - exactly what the resolver produces for one. */
    private val contact = NotificationSender(name = "Asha Rao", monogram = initialsOf("Asha Rao"), isContact = true)

    @Test
    fun `a contact's letter tile is the inbox letter avatar - one initial, the inbox hue`() {
        assertThat(factory.styleFor(contact)).isEqualTo(AvatarStyle.PLAIN)
        val key = factory.tileKeyFor(contact)

        // The inbox shows "A", never the brand mark's "AR".
        assertThat(key.monogram).isEqualTo(plainAvatarInitial("Asha Rao"))
        assertThat(key.monogram).isEqualTo("A")

        // The colour is the inbox derivation over the shade/launcher surface...
        assertThat(key.colorArgb).isEqualTo(plainAvatarColorArgb("Asha Rao", SenderIconFactory.PLAIN_AVATAR_SURFACE_ARGB))
        // ...and that surface is neutral, so the hue is exactly the inbox's.
        val hsl = FloatArray(3).also { ColorUtils.colorToHSL(key.colorArgb, it) }
        assertThat(hsl[0]).isWithin(1f).of(plainAvatarHue("Asha Rao"))
        assertWithMessage("the letter tile must not use the brand-mark hue wheel")
            .that(key.colorArgb)
            .isNotEqualTo(SenderIconFactory.fallbackColorFor("Asha Rao"))
    }

    @Test
    fun `the rendered shortcut avatar and its plate carry the inbox colour`() {
        val avatar = factory.largeIconFor(contact)
        val expected = plainAvatarColorArgb("Asha Rao", SenderIconFactory.PLAIN_AVATAR_SURFACE_ARGB)
        // Inside the disc, clear of the centred glyph: the fill is the inbox colour.
        assertThat(avatar.getPixel(SenderIconFactory.ICON_SIZE_PX / 4, SenderIconFactory.ICON_SIZE_PX / 2)).isEqualTo(expected)
        // The letter is dark on the pastel, as the inbox's onSurface is.
        assertThat(ColorUtils.calculateLuminance(expected)).isGreaterThan(0.4)
        // Full-bleed launcher icon: the plate around the disc is the same colour.
        assertThat(factory.plateColorFor(contact)).isEqualTo(expected)
    }

    @Test
    fun `the notification shares the tile, so shade and launcher agree too`() {
        // One cache entry for one key: the notification's large icon IS the
        // bitmap the shortcut is built from.
        assertThat(factory.largeIconFor(contact)).isSameInstanceAs(factory.largeIconFor(contact))
        assertThat(factory.iconFor(contact)).isNotNull()
    }

    @Test
    fun `a contact photo still renders the photo on a white plate`() {
        val photoUri = "content://com.android.contacts/display_photo/7"
        shadowOf(context.contentResolver).registerInputStream(android.net.Uri.parse(photoUri), ByteArrayInputStream(pngBytes()))
        val withPhoto = contact.copy(photoUri = photoUri)
        assertThat(factory.styleFor(withPhoto)).isEqualTo(AvatarStyle.PHOTO)
        val avatar = factory.largeIconFor(withPhoto)
        assertThat(avatar.getPixel(avatar.width / 2, avatar.height / 2)).isEqualTo(Color.GREEN)
        assertThat(factory.plateColorFor(withPhoto)).isEqualTo(Color.WHITE)
    }

    @Test
    fun `an unreadable photo degrades to the inbox letter avatar, as SenderAvatar's error slot does`() {
        val broken = contact.copy(photoUri = "content://com.android.contacts/display_photo/404")
        assertThat(factory.styleFor(broken)).isEqualTo(AvatarStyle.PHOTO)
        assertThat(factory.tileKeyFor(broken)).isEqualTo(factory.tileKeyFor(contact))
    }

    @Test
    fun `a directory-known sender keeps the brand-mark tile the inbox draws for it`() {
        val known = NotificationSender(name = "Some Bank", monogram = initialsOf("Some Bank"), isKnownSender = true)
        assertThat(factory.styleFor(known)).isEqualTo(AvatarStyle.BRAND_MARK)
        val key = factory.tileKeyFor(known)
        assertThat(key.monogram).isEqualTo("SB")
        assertThat(key.colorArgb).isEqualTo(SenderIconFactory.fallbackColorFor("Some Bank"))
    }

    private fun pngBytes(): ByteArray {
        val bitmap = Bitmap.createBitmap(32, 32, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.GREEN) }
        return ByteArrayOutputStream()
            .also { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            .toByteArray()
    }
}
