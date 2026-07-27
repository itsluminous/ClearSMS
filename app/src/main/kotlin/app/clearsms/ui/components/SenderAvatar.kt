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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.clearsms.ui.theme.ClearSmsTheme
import kotlin.math.abs

private val AVATAR_HUES = listOf(10f, 45f, 90f, 160f, 200f, 230f, 265f, 300f, 330f)

/**
 * Rounded-square (12dp radius) avatar showing the sender's initial on a
 * deterministic tonal color derived from the sender name.
 */
@Composable
fun SenderAvatar(
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
