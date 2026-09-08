package app.clearsms.ui.components

import androidx.compose.animation.core.exponentialDecay
import androidx.compose.animation.core.spring
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.AnchoredDraggableState
import androidx.compose.foundation.gestures.DraggableAnchors
import androidx.compose.foundation.gestures.animateTo
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.horizontalDrag
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.absoluteOffset
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Archive
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.MarkEmailRead
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.changedToUpIgnoreConsumed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.input.pointer.util.addPointerInputChange
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import app.clearsms.R
import app.clearsms.domain.model.SwipeAction
import app.clearsms.domain.model.SwipeDeadZone
import kotlinx.coroutines.launch
import kotlin.math.roundToInt
import kotlin.math.sign

/**
 * Distance the row must travel for a release to trigger the action, and the
 * fling velocity that triggers it regardless of distance. Both are exactly
 * Material3's SwipeToDismissBox defaults (56.dp positional, 125.dp/s
 * velocity) so replacing the component changes gesture RECOGNITION only,
 * never how far a recognised swipe must go.
 */
private val SwipePositionalThreshold = 56.dp
private val SwipeVelocityThreshold = 125.dp

/** Anchor values for the row's drag: rest, or fully revealed either way. */
private enum class RowSwipeAnchor {
    SETTLED,
    START_TO_END,
    END_TO_START,
}

private fun RowSwipeAnchor.toDirection(): SwipeDirection? =
    when (this) {
        RowSwipeAnchor.SETTLED -> null
        RowSwipeAnchor.START_TO_END -> SwipeDirection.START_TO_END
        RowSwipeAnchor.END_TO_START -> SwipeDirection.END_TO_START
    }

/**
 * Wraps an inbox row in a horizontally draggable box whose two directions
 * perform the user-configured [startAction] / [endAction]. [SwipeAction.NONE]
 * disables that direction entirely. The background shows the configured
 * action's icon and label while swiping.
 *
 * Every action runs immediately, Gmail-style: delete and archive surface a
 * transient UNDO snackbar (the system-provider deletion is deferred until
 * the undo window closes), so no blocking confirmation dialog is needed.
 *
 * Gesture recognition is deliberately NOT Material3's SwipeToDismissBox
 * (issue #16): its drag detector claims the gesture as soon as horizontal
 * movement crosses touch slop, so a slightly diagonal scroll flick swipes
 * the row instead of scrolling the list. This implementation drives the same
 * anchored-drag physics from its own pointer loop, which claims only on the
 * axis-dominance verdict of [evaluateSwipeClaim] and yields to the list
 * otherwise. Taps and long-presses pass through untouched (the row is only
 * claimed after dominant horizontal movement past slop).
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun SwipeableMessageItem(
    startAction: SwipeAction,
    endAction: SwipeAction,
    deadZone: SwipeDeadZone,
    onAction: (SwipeAction) -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val density = LocalDensity.current
    val isRtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    val state =
        remember(density) {
            AnchoredDraggableState(
                initialValue = RowSwipeAnchor.SETTLED,
                positionalThreshold = { with(density) { SwipePositionalThreshold.toPx() } },
                velocityThreshold = { with(density) { SwipeVelocityThreshold.toPx() } },
                snapAnimationSpec = spring(),
                decayAnimationSpec = exponentialDecay(),
            )
        }
    val startEnabled = startAction != SwipeAction.NONE
    val endEnabled = endAction != SwipeAction.NONE
    val scope = rememberCoroutineScope()
    // Anchors in raw pointer coordinates, mirroring Material3: a disabled
    // direction simply has no anchor, so the offset clamps at rest and the
    // row cannot travel (or trigger) that way.
    val anchorsFor = { widthPx: Int ->
        val width = widthPx.toFloat()
        DraggableAnchors {
            RowSwipeAnchor.SETTLED at 0f
            if (startEnabled) RowSwipeAnchor.START_TO_END at (if (isRtl) -width else width)
            if (endEnabled) RowSwipeAnchor.END_TO_START at (if (isRtl) width else -width)
        }
    }
    // The visually leading direction, derived from the raw offset sign.
    val visualDirection by remember(isRtl) {
        derivedStateOf {
            val offset = state.offset
            when {
                offset.isNaN() || offset == 0f -> null
                (offset > 0f) != isRtl -> SwipeDirection.START_TO_END
                else -> SwipeDirection.END_TO_START
            }
        }
    }
    Box(
        modifier
            .onSizeChanged { size -> state.updateAnchors(anchorsFor(size.width)) }
            .pointerInput(startAction, endAction, deadZone, state) {
                if (!startEnabled && !endEnabled) return@pointerInput
                val slop = viewConfiguration.touchSlop
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    // Never grab a second gesture while a triggered swipe is
                    // still being handled (mirrors SwipeToDismissBox, which
                    // disables gestures until the state settles again).
                    if (state.currentValue != RowSwipeAnchor.SETTLED) return@awaitEachGesture
                    // The user's dead zone: a touch starting inside the band
                    // can scroll, tap or long-press, but never swipe. Uses the
                    // same SwipeDeadZone.bounds() geometry the settings
                    // preview draws, via blocksTouchAt.
                    if (size.width > 0 &&
                        size.height > 0 &&
                        deadZone.blocksTouchAt(
                            xFraction = down.position.x / size.width,
                            yFraction = down.position.y / size.height,
                        )
                    ) {
                        return@awaitEachGesture
                    }
                    val tracker = VelocityTracker()
                    tracker.addPointerInputChange(down)
                    var totalDx = 0f
                    var totalDy = 0f
                    // Decision stage: accumulate movement until the gesture is
                    // either claimed (dominant horizontal) or yielded.
                    while (true) {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull { it.id == down.id } ?: return@awaitEachGesture
                        // Someone above us (an active list scroll intercepting in
                        // the initial pass) already owns the gesture.
                        if (change.isConsumed) return@awaitEachGesture
                        if (change.changedToUpIgnoreConsumed()) return@awaitEachGesture
                        val delta = change.positionChange()
                        totalDx += delta.x
                        totalDy += delta.y
                        when (evaluateSwipeClaim(totalDx, totalDy, slop)) {
                            SwipeClaimVerdict.YIELD -> return@awaitEachGesture
                            SwipeClaimVerdict.UNDECIDED -> Unit
                            SwipeClaimVerdict.CLAIM -> {
                                tracker.addPointerInputChange(change)
                                change.consume()
                                // Start the drag from the slop edge, like the
                                // platform detectors, so the row does not jump.
                                state.dispatchRawDelta(totalDx - sign(totalDx) * slop)
                                break
                            }
                        }
                    }
                    // Drag stage: the row owns the gesture; consume everything.
                    horizontalDrag(down.id) { change ->
                        tracker.addPointerInputChange(change)
                        state.dispatchRawDelta(change.positionChange().x)
                        change.consume()
                    }
                    val velocity = tracker.calculateVelocity().x
                    scope.launch {
                        state.settle(velocity)
                        // If the settle crossed a trigger anchor, perform the
                        // action and bring the row back to rest ourselves.
                        // (Doing this in a LaunchedEffect keyed on
                        // state.currentValue self-cancels: the return
                        // animation flips currentValue back to SETTLED, which
                        // restarts the effect and kills the running
                        // animation, freezing the row and dropping the
                        // action.) The ViewModel hides the row through its
                        // own state so no dismissed gap is left behind.
                        val direction = state.currentValue.toDirection()
                        if (direction != null) {
                            val action = resolveSwipeAction(direction, startAction, endAction)
                            if (action != SwipeAction.NONE) onAction(action)
                            state.animateTo(RowSwipeAnchor.SETTLED)
                        }
                    }
                }
            },
    ) {
        val direction = visualDirection
        if (direction != null) {
            val startToEnd = direction == SwipeDirection.START_TO_END
            SwipeActionBackground(
                action = if (startToEnd) startAction else endAction,
                alignment = if (startToEnd) Alignment.CenterStart else Alignment.CenterEnd,
                modifier = Modifier.matchParentSize(),
            )
        }
        Box(
            Modifier.absoluteOffset {
                val offset = state.offset
                IntOffset(if (offset.isNaN()) 0 else offset.roundToInt(), 0)
            },
        ) {
            content()
        }
    }
}

@Composable
private fun SwipeActionBackground(
    action: SwipeAction,
    alignment: Alignment,
    modifier: Modifier = Modifier,
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
            modifier
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
