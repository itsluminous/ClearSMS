package app.clearsms.ui.components

import android.widget.Toast
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Send
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.clearsms.R

/**
 * The compose-bar SIM indicator. [visible] only on devices with 2+ active
 * subscriptions - single-SIM devices keep the pre-feature compose bar.
 */
data class SimUiState(
    val visible: Boolean = false,
    /** "SIM 1"/"SIM 2" - the slot of the SIM the next send will use. */
    val label: String = "",
    /** Operator / user-given subscription name, surfaced as a toast on tap. */
    val operatorName: String = "",
)

/**
 * The one compose bar: text field, dual-SIM indicator (tap cycles SIMs and
 * toasts the operator name) and a Send button whose long-press opens the
 * schedule picker. Shared by the conversation screen and the
 * new-conversation screen so send affordances never diverge between them.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun MessageComposerBar(
    draft: String,
    onDraftChange: (String) -> Unit,
    onSend: () -> Unit,
    sim: SimUiState,
    onCycleSim: () -> Unit,
    onScheduleSend: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    Row(
        modifier = modifier.fillMaxWidth().imePadding().padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        OutlinedTextField(
            value = draft,
            onValueChange = onDraftChange,
            modifier = Modifier.weight(1f),
            placeholder = { Text(stringResource(R.string.conversation_reply_hint)) },
            shape = RoundedCornerShape(28.dp),
            maxLines = 4,
        )
        // Compact SIM indicator, dual-SIM devices only: shows the slot the
        // next send uses; tapping cycles SIMs and toasts the operator name.
        if (sim.visible) {
            Surface(
                shape = RoundedCornerShape(14.dp),
                color = MaterialTheme.colorScheme.secondaryContainer,
                modifier =
                    Modifier.clickable(
                        onClick = {
                            onCycleSim()
                            Toast.makeText(context, sim.operatorName, Toast.LENGTH_SHORT).show()
                        },
                        onClickLabel = stringResource(R.string.conversation_sim_switch),
                    ),
            ) {
                Text(
                    text = sim.label,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                )
            }
        }
        // Send: tap sends now, long-press opens the schedule picker. A
        // custom surface because FilledIconButton exposes no long-press.
        val enabled = draft.isNotBlank()
        Surface(
            shape = RoundedCornerShape(20.dp),
            color =
                if (enabled) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.surfaceVariant
                },
            modifier =
                Modifier.combinedClickable(
                    enabled = enabled,
                    onClick = onSend,
                    onClickLabel = stringResource(R.string.action_send),
                    onLongClick = onScheduleSend,
                    onLongClickLabel = stringResource(R.string.conversation_schedule_send),
                ),
        ) {
            Icon(
                Icons.AutoMirrored.Outlined.Send,
                contentDescription = stringResource(R.string.action_send),
                tint =
                    if (enabled) {
                        MaterialTheme.colorScheme.onPrimary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                modifier = Modifier.padding(10.dp),
            )
        }
    }
}
