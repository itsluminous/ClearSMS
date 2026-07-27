package app.clearsms.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Archive
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.MarkEmailRead
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.clearsms.R
import app.clearsms.domain.model.SwipeAction

/**
 * Wraps an inbox row in a [SwipeToDismissBox] whose two directions perform
 * the user-configured [startAction] / [endAction]. [SwipeAction.NONE]
 * disables that direction entirely. The background shows the configured
 * action's icon and label while swiping.
 *
 * Reversible actions (archive, read-toggle) run immediately. Delete is
 * irreversible (it also removes the message from the system SMS provider),
 * so the row first animates back and a confirmation dialog naming
 * [deleteSubject] is shown; the delete only happens on confirm.
 */
@Composable
fun SwipeableMessageItem(
    startAction: SwipeAction,
    endAction: SwipeAction,
    onAction: (SwipeAction) -> Unit,
    deleteSubject: String,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val state = rememberSwipeToDismissBoxState()
    var confirmingDelete by remember { mutableStateOf(false) }
    LaunchedEffect(state.currentValue) {
        val direction =
            when (state.currentValue) {
                SwipeToDismissBoxValue.StartToEnd -> SwipeDirection.START_TO_END
                SwipeToDismissBoxValue.EndToStart -> SwipeDirection.END_TO_START
                SwipeToDismissBoxValue.Settled -> null
            }
        if (direction != null) {
            val action = resolveSwipeAction(direction, startAction, endAction)
            // The row always animates back to rest — a delete only removes
            // it after confirmation, so no dismissed gap is ever left behind.
            state.reset()
            when (val outcome = SwipeConfirmation.onSwipe(action, confirmingDelete)) {
                is SwipeConfirmation.Outcome.Perform -> onAction(outcome.action)
                SwipeConfirmation.Outcome.RequestConfirmation -> confirmingDelete = true
                SwipeConfirmation.Outcome.Ignore -> Unit
            }
        }
    }
    if (confirmingDelete) {
        DeleteConfirmationDialog(
            title = stringResource(R.string.swipe_delete_title),
            text = stringResource(R.string.swipe_delete_message, deleteSubject),
            onConfirm = {
                if (SwipeConfirmation.shouldDeleteOnConfirm(confirmingDelete)) {
                    confirmingDelete = false
                    onAction(SwipeAction.DELETE)
                }
            },
            onDismiss = { confirmingDelete = false },
        )
    }
    SwipeToDismissBox(
        state = state,
        modifier = modifier,
        enableDismissFromStartToEnd = startAction != SwipeAction.NONE,
        enableDismissFromEndToStart = endAction != SwipeAction.NONE,
        backgroundContent = {
            val startToEnd = state.dismissDirection == SwipeToDismissBoxValue.StartToEnd
            val action = if (startToEnd) startAction else endAction
            SwipeActionBackground(
                action = action,
                alignment = if (startToEnd) Alignment.CenterStart else Alignment.CenterEnd,
            )
        },
        content = { content() },
    )
}

@Composable
private fun SwipeActionBackground(
    action: SwipeAction,
    alignment: Alignment,
) {
    val icon: ImageVector?
    val label: String?
    val container: Color
    val content: Color
    when (action) {
        SwipeAction.ARCHIVE -> {
            icon = Icons.Outlined.Archive
            label = stringResource(R.string.action_archive)
            container = MaterialTheme.colorScheme.secondaryContainer
            content = MaterialTheme.colorScheme.onSecondaryContainer
        }
        SwipeAction.DELETE -> {
            icon = Icons.Outlined.Delete
            label = stringResource(R.string.ui_action_delete)
            container = MaterialTheme.colorScheme.errorContainer
            content = MaterialTheme.colorScheme.onErrorContainer
        }
        SwipeAction.TOGGLE_READ -> {
            icon = Icons.Outlined.MarkEmailRead
            label = stringResource(R.string.swipe_action_toggle_read)
            container = MaterialTheme.colorScheme.tertiaryContainer
            content = MaterialTheme.colorScheme.onTertiaryContainer
        }
        SwipeAction.NONE -> {
            icon = null
            label = null
            container = Color.Transparent
            content = Color.Transparent
        }
    }
    Box(
        modifier =
            Modifier
                .fillMaxSize()
                .background(container)
                .padding(horizontal = 24.dp),
        contentAlignment = alignment,
    ) {
        if (icon != null && label != null) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Icon(imageVector = icon, contentDescription = null, tint = content)
                Text(text = label, style = MaterialTheme.typography.labelLarge, color = content)
            }
        }
    }
}
