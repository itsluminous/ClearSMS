package app.clearsms.ui.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.clearsms.R
import app.clearsms.domain.model.LogoBackground
import app.clearsms.ui.theme.ClearSmsTheme
import coil.compose.SubcomposeAsyncImage
import kotlin.math.abs

private val AVATAR_HUES = listOf(10f, 45f, 90f, 160f, 200f, 230f, 265f, 300f, 330f)

/**
 * Sender avatar. With [richAvatars] (the "Show logos and contact photos"
 * setting) the fallback chain is: contact photo → bundled asset logo →
 * curated brand tile (bundled brand table, matched on [sender] and [name]) →
 * category-glyph monogram tile for directory-known senders → plain letter
 * avatar. With the setting off, always the plain letter avatar. Unknown
 * senders always get a letter — never a blank tile. The small category
 * badge ([GlyphBadge]) renders on every variant in both settings states:
 * it identifies the sender kind (bank / wallet / telecom / government…),
 * so turning off logos and photos must not hide it.
 *
 * Every variant clips to [AvatarDefaults.shape]; [size] must be one of the
 * two sanctioned diameters ([AvatarDefaults.size] by default,
 * [AvatarDefaults.compactSize] for dense card corners) so inbox rows, the
 * conversation header, search results, finance cards and alert cards all
 * render the same avatar at a consistent scale.
 */
@Composable
fun SenderAvatar(
    name: String,
    modifier: Modifier = Modifier,
    richAvatars: Boolean = false,
    photoUri: String? = null,
    isKnownSender: Boolean = false,
    glyph: BrandGlyph = BrandGlyph.NONE,
    sender: String? = null,
    size: Dp = AvatarDefaults.size,
) {
    val context = LocalContext.current
    val brand =
        remember(name, sender, richAvatars) {
            if (richAvatars) {
                val catalog = BrandCatalog.get(context)
                sender?.let(catalog::resolve) ?: catalog.resolve(name)
            } else {
                null
            }
        }
    if (richAvatars) {
        LaunchedEffect(Unit) {
            BundledLogos.ensureLoaded(context)
        }
    }
    val bundledKeys by BundledLogos.keys.collectAsStateWithLifecycle()
    val bundledKey = brand?.key?.takeIf { richAvatars && it in bundledKeys }

    val style =
        avatarStyleFor(
            richAvatars = richAvatars,
            photoUri = photoUri,
            isKnownSender = isKnownSender,
            hasBundledLogo = bundledKey != null,
            hasBrand = brand != null,
        )
    when (style) {
        AvatarStyle.PHOTO ->
            Box(modifier = modifier.size(size)) {
                SubcomposeAsyncImage(
                    model = photoUri,
                    contentDescription = stringResource(R.string.avatar_contact_photo),
                    // Center-crop: non-square photos fill the circle, never squashed.
                    contentScale = ContentScale.Crop,
                    error = { PlainAvatar(name = name, size = size) },
                    modifier =
                        Modifier
                            .size(size)
                            .clip(AvatarDefaults.shapeFor(style)),
                )
                GlyphBadge(glyph = avatarBadgeGlyph(brand?.category, glyph), avatarSize = size)
            }
        AvatarStyle.BUNDLED -> {
            // Decoded once per key on IO (BundledLogoCache); the brand tile
            // renders immediately so a list item's first frame never waits
            // on the decode, and a corrupt asset simply keeps the tile.
            var bitmap by remember(bundledKey) { mutableStateOf<ImageBitmap?>(null) }
            LaunchedEffect(bundledKey) {
                bitmap = bundledKey?.let { BundledLogos.bitmap(it) }
            }
            val logo = bitmap
            if (logo != null) {
                // The plate behind the artwork: brand-accurate white by default,
                // a Material You tint, or none (transparent artwork sits on the
                // row). Logos that ship with an opaque background baked into the
                // image look the same either way — see LogoBackground.
                val plate =
                    when (LocalLogoBackground.current) {
                        LogoBackground.WHITE -> Color.White
                        // Not pure black: a near-black plate keeps a subtle edge
                        // against true-black dark surfaces.
                        LogoBackground.DARK -> Color(0xFF1C1B1F)
                        LogoBackground.DYNAMIC -> MaterialTheme.colorScheme.surfaceVariant
                        LogoBackground.NONE -> Color.Transparent
                    }
                Box(modifier = modifier.size(size)) {
                    Image(
                        bitmap = logo,
                        contentDescription = stringResource(R.string.avatar_sender_logo, name),
                        // Fit inside the chosen backing plate: logos are shown whole
                        // (never cropped), and clipping to the shared circle means
                        // transparent-background artwork gets no box edges.
                        contentScale = ContentScale.Fit,
                        modifier =
                            Modifier
                                .size(size)
                                .clip(AvatarDefaults.shapeFor(style))
                                .background(plate)
                                .padding(4.dp),
                    )
                    GlyphBadge(glyph = avatarBadgeGlyph(brand?.category, glyph), avatarSize = size)
                }
            } else {
                SenderBrandMark(name = name, glyph = glyph, modifier = modifier, brand = brand, size = size)
            }
        }
        AvatarStyle.BRAND ->
            SenderBrandMark(name = name, glyph = glyph, modifier = modifier, brand = brand, size = size)
        AvatarStyle.BRAND_MARK ->
            SenderBrandMark(name = name, glyph = glyph, modifier = modifier, size = size)
        AvatarStyle.PLAIN ->
            // The category glyph is informational, so it stays visible even
            // with "Show logos and contact photos" off.
            Box(modifier = modifier.size(size)) {
                PlainAvatar(name = name, size = size)
                GlyphBadge(glyph = avatarBadgeGlyph(brandCategory = null, glyph = glyph), avatarSize = size)
            }
    }
}

/**
 * Letter avatar: the sender's initial on a deterministic tonal color derived
 * from the sender name, clipped to the shared avatar shape.
 */
@Composable
private fun PlainAvatar(
    name: String,
    modifier: Modifier = Modifier,
    size: Dp = AvatarDefaults.size,
) {
    val hue = AVATAR_HUES[abs(name.hashCode()) % AVATAR_HUES.size]
    val tone = Color.hsl(hue, 0.45f, 0.62f)
    val background = tone.copy(alpha = 0.35f).compositeOver(MaterialTheme.colorScheme.surfaceVariant)
    val initial = name.firstOrNull { it.isLetterOrDigit() }?.uppercaseChar()?.toString() ?: "#"
    Box(
        modifier =
            modifier
                .size(size)
                .clip(AvatarDefaults.shapeFor(AvatarStyle.PLAIN))
                .background(background),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = initial,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}

@Preview
@Composable
private fun SenderAvatarPreview() {
    ClearSmsTheme {
        SenderAvatar(name = "HDFC Bank")
    }
}
