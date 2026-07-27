package app.clearsms.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
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
import app.clearsms.ui.theme.ClearSmsTheme
import coil.compose.SubcomposeAsyncImage
import kotlin.math.abs

private val AVATAR_HUES = listOf(10f, 45f, 90f, 160f, 200f, 230f, 265f, 300f, 330f)

/**
 * Sender avatar. With [richAvatars] (the "Show logos and contact photos"
 * setting) the fallback chain is: contact photo → user-supplied logo pack
 * image → curated brand tile (bundled brand table, matched on [sender] and
 * [name]) → category-glyph monogram tile for directory-known senders → plain
 * letter avatar. With the setting off, always the plain letter avatar.
 * Unknown senders always get a letter — never a blank tile.
 */
@Composable
fun SenderAvatar(
    name: String,
    modifier: Modifier = Modifier,
    size: Dp = 44.dp,
    richAvatars: Boolean = false,
    photoUri: String? = null,
    isKnownSender: Boolean = false,
    glyph: BrandGlyph = BrandGlyph.NONE,
    sender: String? = null,
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
        LaunchedEffect(Unit) { LogoPack.ensureLoaded(context) }
    }
    val logoIndex by LogoPack.index.collectAsStateWithLifecycle()
    val logoUri =
        if (richAvatars) {
            remember(logoIndex, brand, sender, name) {
                resolveLogo(logoIndex, brand?.key, sender ?: name)
            }
        } else {
            null
        }

    when (
        avatarStyleFor(
            richAvatars = richAvatars,
            photoUri = photoUri,
            isKnownSender = isKnownSender,
            hasLogo = logoUri != null,
            hasBrand = brand != null,
        )
    ) {
        AvatarStyle.PHOTO ->
            SubcomposeAsyncImage(
                model = photoUri,
                contentDescription = stringResource(R.string.avatar_contact_photo),
                contentScale = ContentScale.Crop,
                error = { PlainAvatar(name = name, size = size) },
                modifier =
                    modifier
                        .size(size)
                        .clip(RoundedCornerShape(12.dp)),
            )
        AvatarStyle.LOGO ->
            SubcomposeAsyncImage(
                model = logoUri,
                contentDescription = stringResource(R.string.avatar_sender_logo, name),
                contentScale = ContentScale.Crop,
                // A corrupt or unreadable file must fall back, never crash or blank.
                error = {
                    SenderBrandMark(name = name, glyph = glyph, size = size, brand = brand)
                },
                modifier =
                    modifier
                        .size(size)
                        .clip(CircleShape),
            )
        AvatarStyle.BRAND ->
            SenderBrandMark(name = name, glyph = glyph, modifier = modifier, size = size, brand = brand)
        AvatarStyle.BRAND_MARK ->
            SenderBrandMark(name = name, glyph = glyph, modifier = modifier, size = size)
        AvatarStyle.PLAIN -> PlainAvatar(name = name, modifier = modifier, size = size)
    }
}

/**
 * Rounded-square (12dp radius) avatar showing the sender's initial on a
 * deterministic tonal color derived from the sender name.
 */
@Composable
private fun PlainAvatar(
    name: String,
    modifier: Modifier = Modifier,
    size: Dp = 44.dp,
) {
    val hue = AVATAR_HUES[abs(name.hashCode()) % AVATAR_HUES.size]
    val tone = Color.hsl(hue, 0.45f, 0.62f)
    val background = tone.copy(alpha = 0.35f).compositeOver(MaterialTheme.colorScheme.surfaceVariant)
    val initial = name.firstOrNull { it.isLetterOrDigit() }?.uppercaseChar()?.toString() ?: "#"
    Box(
        modifier =
            modifier
                .size(size)
                .clip(RoundedCornerShape(12.dp))
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
