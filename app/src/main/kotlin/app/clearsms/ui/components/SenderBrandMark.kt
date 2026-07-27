package app.clearsms.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AccountBalance
import androidx.compose.material.icons.outlined.AccountBalanceWallet
import androidx.compose.material.icons.outlined.Bolt
import androidx.compose.material.icons.outlined.CellTower
import androidx.compose.material.icons.outlined.CreditCard
import androidx.compose.material.icons.outlined.Flight
import androidx.compose.material.icons.outlined.Gavel
import androidx.compose.material.icons.outlined.LocalShipping
import androidx.compose.material.icons.outlined.MedicalServices
import androidx.compose.material.icons.outlined.ShoppingCart
import androidx.compose.material.icons.outlined.TrendingUp
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.clearsms.ui.theme.ClearSmsTheme
import kotlin.math.abs

/**
 * Brand-style avatar for known senders, clipped to the shared
 * [AvatarDefaults.shape] at [AvatarDefaults.size] like every other avatar.
 *
 * With a curated [brand] from the bundled table, it renders a tile filled
 * with the brand's published primary color (a deepened tonal variant in dark
 * theme), the brand monogram in a WCAG-AA-contrasting white or black (see
 * [monogramColorFor]), and a small category badge. Without one, it falls
 * back to the sender name's initials on a deterministic hash color.
 *
 * Deliberately NOT a real logo: the generated tile is an original mark drawn
 * from published facts (name, color, category), used for the many brands
 * whose artwork is not bundled in the APK's asset set.
 */
@Composable
fun SenderBrandMark(
    name: String,
    glyph: BrandGlyph,
    modifier: Modifier = Modifier,
    brand: Brand? = null,
) {
    val brandColor = brand?.let { parseBrandColor(it.color) }
    val background =
        if (brandColor != null) {
            brandTileColor(brandColor, darkTheme = isSystemInDarkTheme())
        } else {
            Color.hsl(BRAND_HUES[abs(name.hashCode()) % BRAND_HUES.size], 0.55f, 0.38f)
        }
    val monogram = brand?.monogram?.take(3)?.ifBlank { null } ?: initialsOf(name)
    val style = if (brand != null) AvatarStyle.BRAND else AvatarStyle.BRAND_MARK
    val size = AvatarDefaults.sizeFor(style)
    val badgeGlyph = avatarBadgeGlyph(brand?.category, glyph)
    Box(modifier = modifier.size(size)) {
        Box(
            modifier =
                Modifier
                    .size(size)
                    .clip(AvatarDefaults.shapeFor(style))
                    .background(background),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = monogram,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                color = monogramColorFor(background),
            )
        }
        GlyphBadge(glyph = badgeGlyph, avatarSize = size)
    }
}

/**
 * The small category badge drawn over an avatar's bottom-end corner. Shared
 * by every avatar variant so the glyph is always present — including on
 * contact photos, bundled logos and the plain letter avatar shown when
 * "Show logos and contact photos" is off.
 */
@Composable
fun BoxScope.GlyphBadge(
    glyph: BrandGlyph,
    avatarSize: Dp,
) {
    glyphIconFor(glyph)?.let { icon ->
        Box(
            modifier =
                Modifier
                    .align(Alignment.BottomEnd)
                    .size(avatarSize * 0.42f)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.surfaceVariant),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(2.dp),
            )
        }
    }
}

/** Up to two initials from the first words of [name]. */
internal fun initialsOf(name: String): String {
    val letters =
        name
            .split(' ', '-', '_')
            .mapNotNull { word -> word.firstOrNull { it.isLetterOrDigit() } }
            .take(2)
    return if (letters.isEmpty()) "#" else letters.joinToString("").uppercase()
}

private fun glyphIconFor(glyph: BrandGlyph) =
    when (glyph) {
        BrandGlyph.BANK -> Icons.Outlined.AccountBalance
        BrandGlyph.CARD -> Icons.Outlined.CreditCard
        BrandGlyph.WALLET -> Icons.Outlined.AccountBalanceWallet
        BrandGlyph.CART -> Icons.Outlined.ShoppingCart
        BrandGlyph.DELIVERY -> Icons.Outlined.LocalShipping
        BrandGlyph.GOVERNMENT -> Icons.Outlined.Gavel
        BrandGlyph.TELECOM -> Icons.Outlined.CellTower
        BrandGlyph.UTILITY -> Icons.Outlined.Bolt
        BrandGlyph.INVESTMENT -> Icons.Outlined.TrendingUp
        BrandGlyph.HEALTH -> Icons.Outlined.MedicalServices
        BrandGlyph.TRAVEL -> Icons.Outlined.Flight
        BrandGlyph.NONE -> null
    }

private val BRAND_HUES = listOf(8f, 32f, 152f, 176f, 206f, 226f, 258f, 288f, 340f)

@Preview
@Composable
private fun SenderBrandMarkPreview() {
    ClearSmsTheme {
        SenderBrandMark(name = "HDFC Bank", glyph = BrandGlyph.BANK)
    }
}
