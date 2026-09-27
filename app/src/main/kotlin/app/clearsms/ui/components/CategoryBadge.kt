package app.clearsms.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import app.clearsms.domain.model.Category
import app.clearsms.domain.model.InboxPill
import app.clearsms.ui.theme.ClearSmsTheme

/** Human-readable label for a primary category. */
fun Category.displayName(): String =
    when (this) {
        Category.IMPORTANT -> "Important"
        Category.PROMOTIONAL -> "Promotional"
        Category.PERSONAL -> "Personal"
        Category.UNKNOWN -> "Unknown"
        Category.OTP -> "OTP"
        Category.SPAM -> "Spam"
    }

/**
 * Built-in label of an Inbox pill - what it shows until the user renames it
 * in Settings. Every pill reuses its category's [displayName], so a category
 * tag and its pill agree by construction.
 */
fun InboxPill.defaultLabel(): String = category.displayName()

/** Subtle tonal badge showing a message's category under the sender name. */
@Composable
fun CategoryBadge(
    category: Category,
    modifier: Modifier = Modifier,
) {
    val container =
        when (category) {
            Category.IMPORTANT -> MaterialTheme.colorScheme.primaryContainer
            Category.OTP -> MaterialTheme.colorScheme.tertiaryContainer
            else -> MaterialTheme.colorScheme.secondaryContainer
        }
    val content =
        when (category) {
            Category.IMPORTANT -> MaterialTheme.colorScheme.onPrimaryContainer
            Category.OTP -> MaterialTheme.colorScheme.onTertiaryContainer
            else -> MaterialTheme.colorScheme.onSecondaryContainer
        }
    Text(
        text = category.displayName(),
        style = MaterialTheme.typography.labelSmall,
        color = content,
        modifier =
            modifier
                .clip(RoundedCornerShape(8.dp))
                .background(container)
                .padding(horizontal = 8.dp, vertical = 2.dp),
    )
}

@Preview
@Composable
private fun CategoryBadgePreview() {
    ClearSmsTheme {
        CategoryBadge(category = Category.IMPORTANT)
    }
}
