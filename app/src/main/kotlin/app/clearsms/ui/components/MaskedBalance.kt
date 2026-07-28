package app.clearsms.ui.components

import androidx.compose.foundation.layout.Row
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
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
 * A monetary balance behind the "Show balance" privacy gate.
 *
 * - Not gated (setting ON): plain [AmountText], no eye — today's behaviour.
 * - Gated and hidden: [BalanceMask.MASK] plus an eye whose tap asks the
 *   caller to run device authentication. The masked text is removed from
 *   the accessibility tree ([clearAndSetSemantics]) and replaced with a
 *   generic "Balance hidden" description, so TalkBack can never read a
 *   value that the screen does not show.
 * - Gated and revealed: the value plus an eye that re-conceals immediately
 *   (hiding needs no authentication).
 *
 * The eye is an [IconButton] — Material3 guarantees the 48dp minimum touch
 * target — and its contentDescription flips between "Show balance" and
 * "Hide balance" with state.
 */
@Composable
fun MaskedBalance(
    amount: Double,
    kind: AmountKind,
    gated: Boolean,
    revealed: Boolean,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier,
    style: TextStyle = MaterialTheme.typography.titleMedium,
) {
    if (!gated) {
        AmountText(amount = amount, kind = kind, modifier = modifier, style = style)
        return
    }
    Row(verticalAlignment = Alignment.CenterVertically, modifier = modifier) {
        if (revealed) {
            AmountText(amount = amount, kind = kind, style = style)
        } else {
            val hiddenDescription = stringResource(R.string.balance_hidden)
            Text(
                text = BalanceMask.MASK,
                style = style,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.clearAndSetSemantics { contentDescription = hiddenDescription },
            )
        }
        BalanceEyeButton(revealed = revealed, onToggle = onToggle)
    }
}

/** The reveal/conceal eye, shared by [MaskedBalance] and bespoke layouts. */
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
