package app.clearsms.ui.components

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import app.clearsms.R

/** What the summary card's reveal affordance shows for a (gated, revealed) pair. */
enum class RevealButtonState {
    /** Balances are hidden: show the labelled "Show balances" button. */
    SHOW_REVEAL,

    /** Unlocked this session: show a quiet "Hide balances" affordance. */
    SHOW_HIDE,

    /** Setting is ON - nothing is masked, so no control at all. */
    NONE,
}

/** Pure visibility rule for the in-card reveal button, kept separate for JVM tests. */
object BalanceRevealButton {
    fun state(
        gated: Boolean,
        revealed: Boolean,
    ): RevealButtonState =
        when {
            !gated -> RevealButtonState.NONE
            revealed -> RevealButtonState.SHOW_HIDE
            else -> RevealButtonState.SHOW_REVEAL
        }
}

/**
 * The labelled reveal control that lives INSIDE the month-summary card,
 * right next to the masked figures (the old bare top-bar eye floated in
 * empty app-bar space and was too easy to miss).
 *
 * - Hidden state: a filled "Show balances" button. Colors deliberately swap
 *   the container pair - onPrimaryContainer fill with primaryContainer
 *   content - because Material guarantees that pair legible contrast on the
 *   card's primaryContainer surface in BOTH light and dark themes.
 * - Revealed state: a quiet "Hide balances" text button. It stays (rather
 *   than disappearing) because with the top-bar eye gone this is the only
 *   manual re-mask on the screen, and a reveal otherwise lasts the whole
 *   foreground session.
 * - Tapping is handled entirely by the button (it consumes its own clicks),
 *   so the surrounding card's expand tap is never swallowed.
 * - [Modifier.heightIn] pins the 48dp touch target; the label doubles as a
 *   contentDescription for real button semantics.
 */
@Composable
fun BalanceRevealCardButton(
    state: RevealButtonState,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier,
) {
    when (state) {
        RevealButtonState.SHOW_REVEAL -> {
            val label = stringResource(R.string.balance_show_balances)
            Button(
                onClick = onToggle,
                colors =
                    ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.onPrimaryContainer,
                        contentColor = MaterialTheme.colorScheme.primaryContainer,
                    ),
                modifier = modifier.heightIn(min = 48.dp).semantics { contentDescription = label },
            ) {
                Icon(Icons.Outlined.Visibility, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(label)
            }
        }
        RevealButtonState.SHOW_HIDE -> {
            val label = stringResource(R.string.balance_hide_balances)
            TextButton(
                onClick = onToggle,
                colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.onPrimaryContainer),
                modifier = modifier.heightIn(min = 48.dp).semantics { contentDescription = label },
            ) {
                Icon(Icons.Outlined.VisibilityOff, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(label)
            }
        }
        RevealButtonState.NONE -> Unit
    }
}
