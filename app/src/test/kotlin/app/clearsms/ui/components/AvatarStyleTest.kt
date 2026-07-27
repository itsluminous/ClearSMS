package app.clearsms.ui.components

import app.clearsms.domain.model.SubCategory
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class AvatarStyleTest {
    @Test
    fun `rich mode with a photo shows the contact photo`() {
        assertThat(avatarStyleFor(richAvatars = true, photoUri = "content://photo/1", isKnownSender = false))
            .isEqualTo(AvatarStyle.PHOTO)
    }

    @Test
    fun `rich mode without a photo shows a brand mark for known senders`() {
        assertThat(avatarStyleFor(richAvatars = true, photoUri = null, isKnownSender = true))
            .isEqualTo(AvatarStyle.BRAND_MARK)
    }

    @Test
    fun `rich mode falls back to plain for unknown senders without photos`() {
        assertThat(avatarStyleFor(richAvatars = true, photoUri = null, isKnownSender = false))
            .isEqualTo(AvatarStyle.PLAIN)
    }

    @Test
    fun `disabled rich mode always renders plain`() {
        assertThat(avatarStyleFor(richAvatars = false, photoUri = "content://photo/1", isKnownSender = true))
            .isEqualTo(AvatarStyle.PLAIN)
        assertThat(avatarStyleFor(richAvatars = false, photoUri = null, isKnownSender = true))
            .isEqualTo(AvatarStyle.PLAIN)
    }

    @Test
    fun `glyph follows the message sub-category first`() {
        assertThat(brandGlyphFor(SubCategory.BANK_ALERT, "Anything")).isEqualTo(BrandGlyph.BANK)
        assertThat(brandGlyphFor(SubCategory.GOVERNMENT, "Anything")).isEqualTo(BrandGlyph.GOVERNMENT)
        assertThat(brandGlyphFor(SubCategory.RECHARGE, "Anything")).isEqualTo(BrandGlyph.TELECOM)
        assertThat(brandGlyphFor(SubCategory.DELIVERY, "Anything")).isEqualTo(BrandGlyph.CART)
    }

    @Test
    fun `glyph falls back to sender-name keywords`() {
        assertThat(brandGlyphFor(null, "HDFC Bank")).isEqualTo(BrandGlyph.BANK)
        assertThat(brandGlyphFor(null, "Airtel")).isEqualTo(BrandGlyph.TELECOM)
        assertThat(brandGlyphFor(null, "Amazon")).isEqualTo(BrandGlyph.CART)
        assertThat(brandGlyphFor(null, "Someone")).isEqualTo(BrandGlyph.NONE)
    }

    @Test
    fun `initials use up to two words`() {
        assertThat(initialsOf("HDFC Bank")).isEqualTo("HB")
        assertThat(initialsOf("Amazon")).isEqualTo("A")
        assertThat(initialsOf("")).isEqualTo("#")
    }
}
