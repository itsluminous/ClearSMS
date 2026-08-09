package app.clearsms.ui.components

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import app.clearsms.R

/** Pure helpers behind [MaskedBalance], kept separate for JVM tests. */
object BalanceMask {
    /**
     * The placeholder shown instead of a hidden balance. Fixed-width dots
     * carry no magnitude information (every balance masks identically).
     */
    const val MASK = "₹\u00A0••••••"

    /** True when a gated value should render as [MASK]. */
    fun isMasked(
        gated: Boolean,
        revealed: Boolean,
    ): Boolean = gated && !revealed
}

/**
 * A monetary amount behind the "Show balance" privacy gate - display only,
 * with NO inline eye. Revealing is a screen-level action (one eye in the
 * top bar) because the gate is global: revealing one balance reveals them
 * all, so a per-row control was pure noise stealing row width.
 *
 * - Not gated (setting ON): plain [AmountText] - today's behaviour.
 * - Gated and hidden: [BalanceMask.MASK]; the masked text is removed from
 *   the accessibility tree ([clearAndSetSemantics]) and replaced with a
 *   generic "Balance hidden" description, so TalkBack can never read a
 *   value that the screen does not show.
 * - Gated and revealed: the value.
 */
@Composable
fun MaskedAmountText(
    amount: Double,
    kind: AmountKind,
    gated: Boolean,
    revealed: Boolean,
    modifier: Modifier = Modifier,
    style: TextStyle = MaterialTheme.typography.titleMedium,
) {
    if (!gated || revealed) {
        AmountText(amount = amount, kind = kind, modifier = modifier, style = style)
        return
    }
    val hiddenDescription = stringResource(R.string.balance_hidden)
    Text(
        text = BalanceMask.MASK,
        style = style,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier.clearAndSetSemantics { contentDescription = hiddenDescription },
    )
}

/**
 * The reveal/conceal eye - ONE per screen (top bar / section header), never
 * per row. An [IconButton] guarantees the 48dp minimum touch target.
 */
@Composable
fun BalanceEyeButton(
    revealed: Boolean,
    onToggle: () -> Unit,
) {
    IconButton(onClick = onToggle) {
        Icon(
            imageVector = if (revealed) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility,
            contentDescription =
                stringResource(
                    if (revealed) R.string.balance_conceal else R.string.balance_reveal,
                ),
        )
    }
}
