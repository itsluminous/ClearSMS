package app.clearsms.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material.icons.outlined.KeyboardArrowUp
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import app.clearsms.R

/**
 * Reorders a screen's filter pills.
 *
 * Deliberately move-up / move-down buttons rather than drag-and-drop: every
 * control is a real focusable button with its own content description, so the
 * whole dialog works with TalkBack and switch access, which a drag handle in a
 * dialog does not. Pills can only be reordered, never removed.
 */
@Composable
fun <T> PillOrderDialog(
    title: String,
    order: List<T>,
    label: @Composable (T) -> String,
    onConfirm: (List<T>) -> Unit,
    onReset: () -> Unit,
    onDismiss: () -> Unit,
) {
    val working = remember(order) { mutableStateListOf<T>().apply { addAll(order) } }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                working.forEachIndexed { index, item ->
                    val name = label(item)
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text(text = name, style = MaterialTheme.typography.bodyLarge)
                        Row {
                            IconButton(
                                onClick = { working.move(index, index - 1) },
                                enabled = index > 0,
                            ) {
                                Icon(
                                    Icons.Outlined.KeyboardArrowUp,
                                    contentDescription = stringResource(R.string.pill_order_move_up, name),
                                )
                            }
                            IconButton(
                                onClick = { working.move(index, index + 1) },
                                enabled = index < working.lastIndex,
                            ) {
                                Icon(
                                    Icons.Outlined.KeyboardArrowDown,
                                    contentDescription = stringResource(R.string.pill_order_move_down, name),
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(working.toList()) }) {
                Text(stringResource(R.string.action_save))
            }
        },
        dismissButton = {
            TextButton(onClick = onReset) {
                Text(stringResource(R.string.pill_order_reset))
            }
        },
    )
}

/** Moves the item at [from] to [to], ignoring out-of-range targets. */
internal fun <T> MutableList<T>.move(
    from: Int,
    to: Int,
) {
    if (from !in indices || to !in indices || from == to) return
    add(to, removeAt(from))
}
