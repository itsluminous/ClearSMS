package app.clearsms.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.clearsms.R
import app.clearsms.ui.theme.ClearSmsTheme
import coil.compose.AsyncImage
import kotlin.math.abs

private val AVATAR_HUES = listOf(10f, 45f, 90f, 160f, 200f, 230f, 265f, 300f, 330f)

/**
 * Sender avatar with three renderings (see [avatarStyleFor]): the contact's
 * photo, a [SenderBrandMark] for directory-known senders, or the plain
 * initial-on-tonal-color square. Photos and brand marks require [richAvatars]
 * (the "Show logos and contact photos" setting) to be on.
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
) {
    when (avatarStyleFor(richAvatars, photoUri, isKnownSender)) {
        AvatarStyle.PHOTO ->
            AsyncImage(
                model = photoUri,
                contentDescription = stringResource(R.string.avatar_contact_photo),
                contentScale = ContentScale.Crop,
                modifier =
                    modifier
                        .size(size)
                        .clip(RoundedCornerShape(12.dp)),
            )
        AvatarStyle.BRAND_MARK -> SenderBrandMark(name = name, glyph = glyph, modifier = modifier, size = size)
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
