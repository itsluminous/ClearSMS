package app.clearsms.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import app.clearsms.R
import app.clearsms.domain.model.InboxPill
import app.clearsms.ui.components.defaultLabel
import app.clearsms.ui.inbox.InboxPillConfig

/**
 * Which Inbox pills are shown (issue #49). One checkbox per pill in the
 * user's order, labelled with the pill's current display name. There is NO
 * minimum: unticking every pill is allowed and simply removes the pill row
 * (see [InboxPillConfig]); the hint says so.
 */
@Composable
fun InboxVisiblePillsDialog(
    pills: InboxPillConfig,
    onConfirm: (hidden: Set<InboxPill>) -> Unit,
    onDismiss: () -> Unit,
) {
    var hidden by remember(pills) { mutableStateOf(pills.hidden) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.settings_inbox_visible_pills)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text(
                    text = stringResource(R.string.settings_inbox_visible_pills_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                pills.ordered.forEach { pill ->
                    val checked = pill !in hidden
                    Row(
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .heightIn(min = 48.dp)
                                .toggleable(
                                    value = checked,
                                    onValueChange = { hidden = if (it) hidden - pill else hidden + pill },
                                    role = Role.Checkbox,
                                ).padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Checkbox(checked = checked, onCheckedChange = null)
                        Spacer(Modifier.padding(horizontal = 6.dp))
                        Text(pills.label(pill, InboxPill::defaultLabel))
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(hidden) }) { Text(stringResource(R.string.action_save)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
        },
    )
}
