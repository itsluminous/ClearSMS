package app.clearsms.ui.components

import androidx.compose.foundation.shape.CircleShape
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
    fun `bundled asset logo beats the generated brand tile`() {
        assertThat(
            avatarStyleFor(
                richAvatars = true,
                photoUri = null,
                isKnownSender = true,
                hasBundledLogo = true,
                hasBrand = true,
            ),
        ).isEqualTo(AvatarStyle.BUNDLED)
    }

    @Test
    fun `contact photo beats every logo source`() {
        assertThat(
            avatarStyleFor(
                richAvatars = true,
                photoUri = "content://photo/1",
                isKnownSender = true,
                hasBundledLogo = true,
                hasBrand = true,
            ),
        ).isEqualTo(AvatarStyle.PHOTO)
    }

    @Test
    fun `disabled rich mode stays plain even with a bundled logo available`() {
        assertThat(
            avatarStyleFor(
                richAvatars = false,
                photoUri = "content://photo/1",
                isKnownSender = true,
                hasBundledLogo = true,
                hasBrand = true,
            ),
        ).isEqualTo(AvatarStyle.PLAIN)
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

/**
 * The user-reported inconsistency was generated tiles and bundled logos not
 * sharing a shape; [AvatarDefaults] is now the single source of truth every
 * variant resolves through. These tests pin that invariant.
 */
class AvatarShapeConsistencyTest {
    @Test
    fun `every avatar variant resolves to the same shape`() {
        AvatarStyle.entries.forEach { style ->
            assertThat(AvatarDefaults.shapeFor(style)).isEqualTo(AvatarDefaults.shape)
        }
    }

    @Test
    fun `every avatar variant resolves to the same diameter`() {
        AvatarStyle.entries.forEach { style ->
            assertThat(AvatarDefaults.sizeFor(style)).isEqualTo(AvatarDefaults.size)
        }
    }

    @Test
    fun `the shared avatar shape is circular`() {
        assertThat(AvatarDefaults.shape).isEqualTo(CircleShape)
    }
}
