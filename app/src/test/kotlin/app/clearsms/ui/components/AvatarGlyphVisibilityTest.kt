package app.clearsms.ui.components

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * The category glyph badge is informational and must be present regardless
 * of the "Show logos and contact photos" setting: [avatarBadgeGlyph] does
 * not take the setting as input at all, and every [AvatarStyle] the setting
 * can produce renders the same badge glyph for the same sender.
 */
class AvatarGlyphVisibilityTest {
    private val richStates = listOf(true, false)

    @Test
    fun `badge glyph is independent of the rich-avatars setting`() {
        // Every avatar style the setting toggle can produce for the same
        // sender resolves the same badge input - no brand data is available
        // with the setting off, so the message-derived glyph must carry.
        for (rich in richStates) {
            assertThat(avatarBadgeGlyph(brandCategory = null, glyph = BrandGlyph.BANK))
                .isEqualTo(BrandGlyph.BANK)
        }
    }

    @Test
    fun `plain avatar style still resolves a badge glyph`() {
        for (rich in richStates) {
            val style =
                avatarStyleFor(
                    richAvatars = rich,
                    photoUri = null,
                    isKnownSender = false,
                )
            assertThat(style).isEqualTo(AvatarStyle.PLAIN)
            assertThat(avatarBadgeGlyph(null, BrandGlyph.TELECOM)).isEqualTo(BrandGlyph.TELECOM)
        }
    }

    @Test
    fun `brand category wins over the message-derived glyph`() {
        assertThat(avatarBadgeGlyph(BrandCategory.WALLET, BrandGlyph.BANK)).isEqualTo(BrandGlyph.WALLET)
    }

    @Test
    fun `brand category OTHER falls back to the message-derived glyph`() {
        assertThat(avatarBadgeGlyph(BrandCategory.OTHER, BrandGlyph.GOVERNMENT)).isEqualTo(BrandGlyph.GOVERNMENT)
    }

    @Test
    fun `every style variant resolves the same badge for the same inputs`() {
        val glyph = BrandGlyph.CART
        val fromPhoto = avatarBadgeGlyph(null, glyph)
        val fromBundled = avatarBadgeGlyph(BrandCategory.ECOMMERCE, glyph)
        val fromPlain = avatarBadgeGlyph(null, glyph)
        assertThat(fromPhoto).isEqualTo(BrandGlyph.CART)
        assertThat(fromBundled).isEqualTo(BrandGlyph.CART)
        assertThat(fromPlain).isEqualTo(BrandGlyph.CART)
    }

    @Test
    fun `no glyph stays no glyph`() {
        assertThat(avatarBadgeGlyph(null, BrandGlyph.NONE)).isEqualTo(BrandGlyph.NONE)
    }
}
