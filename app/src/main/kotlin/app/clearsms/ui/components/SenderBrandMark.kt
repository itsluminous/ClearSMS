package app.clearsms.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AccountBalance
import androidx.compose.material.icons.outlined.CellTower
import androidx.compose.material.icons.outlined.Gavel
import androidx.compose.material.icons.outlined.ShoppingCart
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
 * Brand-style avatar for known senders: the sender name's initials on a
 * deterministic, saturated color derived from the name hash, plus a small
 * category glyph (bank / cart / government / telecom).
 *
 * Deliberately NOT a real logo: bundling third-party bank/brand trademarks
 * would require individual licensing and puts an open-source APK at legal
 * risk, so the app synthesizes a stable visual identity instead.
 */
@Composable
fun SenderBrandMark(
    name: String,
    glyph: BrandGlyph,
    modifier: Modifier = Modifier,
    size: Dp = 44.dp,
) {
    val hue = BRAND_HUES[abs(name.hashCode()) % BRAND_HUES.size]
    val background = Color.hsl(hue, 0.55f, 0.38f)
    Box(modifier = modifier.size(size)) {
        Box(
            modifier =
                Modifier
                    .size(size)
                    .clip(RoundedCornerShape(12.dp))
                    .background(background),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = initialsOf(name),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                color = Color.White,
            )
        }
        glyphIconFor(glyph)?.let { icon ->
            Box(
                modifier =
                    Modifier
                        .align(Alignment.BottomEnd)
                        .size(size * 0.42f)
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
        BrandGlyph.CART -> Icons.Outlined.ShoppingCart
        BrandGlyph.GOVERNMENT -> Icons.Outlined.Gavel
        BrandGlyph.TELECOM -> Icons.Outlined.CellTower
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
