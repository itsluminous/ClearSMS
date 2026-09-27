package app.clearsms.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DragIndicator
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import app.clearsms.R

/**
 * Reorders a screen's filter pills - THE order dialog the Inbox, Finance and
 * Alerts settings all open, so the three cannot drift apart.
 *
 * Each row carries a dotted drag handle on the left; dragging it moves the
 * row, which lifts and follows the finger while its neighbours step aside.
 * All of the arithmetic lives in [PillDragState] / [movedPill], which are
 * pure and unit-tested; this composable is the thin gesture shell over them.
 * The order is handed to [onOrderChange] once per completed drag - never
 * while the finger is still moving - and hidden pills are listed too, since
 * order and visibility are independent preferences.
 *
 * Accessibility is load-bearing here: a drag handle alone is unusable with a
 * screen reader, switch access, or without fine motor control - and the
 * up/down buttons this replaced were that path. So every handle is a
 * focusable node whose content description names the row, announces its
 * position, and exposes "Move up" / "Move down" as custom accessibility
 * actions, each committing immediately like a completed drag.
 * PillOrderDialogContractTest pins this.
 */
@Composable
fun <T> PillOrderDialog(
    title: String,
    order: List<T>,
    label: @Composable (T) -> String,
    onOrderChange: (List<T>) -> Unit,
    onReset: () -> Unit,
    onDismiss: () -> Unit,
) {
    var drag by remember(order) { mutableStateOf(PillDragState(order)) }
    val rowHeights = remember { mutableMapOf<Int, Int>() }

    fun settle(settled: PillDragState.Settled<T>) {
        drag = settled.state
        settled.committed?.let(onOrderChange)
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text(
                    text = stringResource(R.string.pill_order_drag_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 4.dp),
                )
                drag.order.forEachIndexed { index, item ->
                    val name = label(item)
                    val dragging = drag.active == index
                    val handle = stringResource(R.string.pill_order_drag_handle, name)
                    val moveUp = stringResource(R.string.pill_order_move_up, name)
                    val moveDown = stringResource(R.string.pill_order_move_down, name)
                    val position = stringResource(R.string.pill_order_position, index + 1, drag.order.size)
                    Row(
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .heightIn(min = 48.dp)
                                .zIndex(if (dragging) 1f else 0f)
                                .graphicsLayer {
                                    translationY = if (dragging) drag.offsetPx else 0f
                                    shadowElevation = if (dragging) 6.dp.toPx() else 0f
                                }.background(
                                    if (dragging) MaterialTheme.colorScheme.surfaceContainerHigh else Color.Transparent,
                                    MaterialTheme.shapes.small,
                                ).onSizeChanged { rowHeights[index] = it.height },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(
                            modifier =
                                Modifier
                                    .size(48.dp)
                                    .semantics {
                                        contentDescription = handle
                                        stateDescription = position
                                        customActions =
                                            buildList {
                                                if (index > 0) {
                                                    add(
                                                        CustomAccessibilityAction(moveUp) {
                                                            settle(drag.moveBy(index, -1))
                                                            true
                                                        },
                                                    )
                                                }
                                                if (index < drag.order.lastIndex) {
                                                    add(
                                                        CustomAccessibilityAction(moveDown) {
                                                            settle(drag.moveBy(index, +1))
                                                            true
                                                        },
                                                    )
                                                }
                                            }
                                    }.focusable()
                                    .pointerInput(index) {
                                        detectDragGestures(
                                            onDragStart = { drag = drag.begin(index) },
                                            onDrag = { change, delta ->
                                                change.consume()
                                                val height = drag.active?.let { rowHeights[it] } ?: 0
                                                drag = drag.dragBy(delta.y, height.toFloat())
                                            },
                                            onDragEnd = { settle(drag.finish()) },
                                            onDragCancel = { drag = drag.cancel() },
                                        )
                                    },
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(
                                imageVector = Icons.Default.DragIndicator,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Spacer(Modifier.width(4.dp))
                        Text(text = name, style = MaterialTheme.typography.bodyLarge)
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_done)) }
        },
        dismissButton = {
            TextButton(onClick = onReset) { Text(stringResource(R.string.pill_order_reset)) }
        },
    )
}
