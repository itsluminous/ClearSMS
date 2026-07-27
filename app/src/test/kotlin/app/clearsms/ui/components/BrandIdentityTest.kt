package app.clearsms.ui.components

import androidx.compose.ui.graphics.Color
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class BrandContrastTest {
    @Test
    fun `dark brand colors get white monograms`() {
        assertThat(monogramColorFor(Color(0xFF004C8F))).isEqualTo(Color.White) // deep blue
        assertThat(monogramColorFor(Color(0xFF97144D))).isEqualTo(Color.White) // burgundy
        assertThat(monogramColorFor(Color(0xFF0D0D0D))).isEqualTo(Color.White) // near-black
    }

    @Test
    fun `light brand colors get black monograms`() {
        assertThat(monogramColorFor(Color(0xFF00BAF2))).isEqualTo(Color.Black) // light cyan
        assertThat(monogramColorFor(Color(0xFF84C225))).isEqualTo(Color.Black) // lime green
        assertThat(monogramColorFor(Color(0xFFF7A800))).isEqualTo(Color.Black) // amber
    }

    @Test
    fun `chosen monogram color always wins the contrast comparison`() {
        BrandIndex.parse(BrandResolutionTest.bundledBrandsJson()).brands.forEach { brand ->
            listOf(false, true).forEach { dark ->
                val tile = brandTileColor(parseBrandColor(brand.color)!!, darkTheme = dark)
                val text = monogramColorFor(tile)
                val other = if (text == Color.White) Color.Black else Color.White
                assertThat(contrastRatio(tile, text)).isAtLeast(contrastRatio(tile, other))
            }
        }
    }

    @Test
    fun `relative luminance matches WCAG anchors`() {
        assertThat(relativeLuminance(Color.Black)).isWithin(1e-6).of(0.0)
        assertThat(relativeLuminance(Color.White)).isWithin(1e-6).of(1.0)
        assertThat(contrastRatio(Color.Black, Color.White)).isWithin(1e-6).of(21.0)
    }

    @Test
    fun `parseBrandColor handles valid and malformed hex`() {
        assertThat(parseBrandColor("#FF9900")).isEqualTo(Color(0xFFFF9900))
        assertThat(parseBrandColor("#GGGGGG")).isNull()
        assertThat(parseBrandColor("#123")).isNull()
        assertThat(parseBrandColor("")).isNull()
    }
}

class AvatarFallbackTest {
    @Test
    fun `chain order is photo then logo then brand then category then letter`() {
        val all =
            avatarStyleFor(
                richAvatars = true,
                photoUri = "content://photo/1",
                isKnownSender = true,
                hasLogo = true,
                hasBrand = true,
            )
        assertThat(all).isEqualTo(AvatarStyle.PHOTO)
        assertThat(avatarStyleFor(true, null, true, hasLogo = true, hasBrand = true))
            .isEqualTo(AvatarStyle.LOGO)
        assertThat(avatarStyleFor(true, null, true, hasLogo = false, hasBrand = true))
            .isEqualTo(AvatarStyle.BRAND)
        assertThat(avatarStyleFor(true, null, true, hasLogo = false, hasBrand = false))
            .isEqualTo(AvatarStyle.BRAND_MARK)
        assertThat(avatarStyleFor(true, null, false, hasLogo = false, hasBrand = false))
            .isEqualTo(AvatarStyle.PLAIN)
    }

    @Test
    fun `rich avatars off is always plain`() {
        assertThat(avatarStyleFor(false, "content://photo/1", true, hasLogo = true, hasBrand = true))
            .isEqualTo(AvatarStyle.PLAIN)
    }

    @Test
    fun `unknown sender lands on letter avatar never a blank tile`() {
        val index = BrandIndex.parse(BrandResolutionTest.bundledBrandsJson())
        val brand = index.resolve("AIRSVD")
        val style =
            avatarStyleFor(
                richAvatars = true,
                photoUri = null,
                isKnownSender = false,
                hasLogo = false,
                hasBrand = brand != null,
            )
        assertThat(style).isEqualTo(AvatarStyle.PLAIN)
    }

    @Test
    fun `every brand category maps to a glyph`() {
        BrandCategory.entries.forEach { category ->
            val glyph = category.toGlyph()
            if (category == BrandCategory.OTHER) {
                assertThat(glyph).isEqualTo(BrandGlyph.NONE)
            } else {
                assertThat(glyph).isNotEqualTo(BrandGlyph.NONE)
            }
        }
    }
}

class LogoPackMatchTest {
    @Test
    fun `filename keys accept image extensions case-insensitively`() {
        assertThat(logoKeyForFileName("hdfc.png")).isEqualTo("hdfc")
        assertThat(logoKeyForFileName("HDFCBK.PNG")).isEqualTo("hdfcbk")
        assertThat(logoKeyForFileName("Paytm.WebP")).isEqualTo("paytm")
        assertThat(logoKeyForFileName("icici.jpeg")).isEqualTo("icici")
        assertThat(logoKeyForFileName("axis.jpg")).isEqualTo("axis")
    }

    @Test
    fun `non-image or extensionless files are rejected`() {
        assertThat(logoKeyForFileName("notes.txt")).isNull()
        assertThat(logoKeyForFileName("archive.zip")).isNull()
        assertThat(logoKeyForFileName("README")).isNull()
        assertThat(logoKeyForFileName(".png")).isNull()
    }

    @Test
    fun `matching prefers brand key then normalized sender id then raw sender`() {
        val index = mapOf("hdfc" to 1, "hdfcbk" to 2, "vm-hdfcbk" to 3)
        assertThat(resolveLogo(index, brandKey = "hdfc", sender = "VM-HDFCBK")).isEqualTo(1)
        assertThat(resolveLogo(index - "hdfc", brandKey = "hdfc", sender = "VM-HDFCBK")).isEqualTo(2)
        assertThat(resolveLogo(mapOf("vm-hdfcbk" to 3), brandKey = null, sender = "VM-HDFCBK")).isEqualTo(3)
    }

    @Test
    fun `matching is case-insensitive and misses cleanly`() {
        val index = mapOf("hdfcbk" to "uri")
        assertThat(resolveLogo(index, brandKey = null, sender = "vm-HdFcBk")).isEqualTo("uri")
        assertThat(resolveLogo(index, brandKey = "sbi", sender = "SBIINB")).isNull()
        assertThat(resolveLogo(emptyMap<String, String>(), brandKey = "hdfc", sender = "HDFCBK")).isNull()
    }
}
