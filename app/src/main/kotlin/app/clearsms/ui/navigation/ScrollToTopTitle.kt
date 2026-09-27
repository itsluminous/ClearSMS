package app.clearsms.ui.navigation

import androidx.compose.animation.core.animate
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarScrollBehavior
import androidx.compose.material3.TopAppBarState
import androidx.compose.material3.Typography
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.clearsms.R
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch

/**
 * Pure decision for the title tap (issue #46): the Twitter/X gesture of
 * tapping the app title to return to the top of the list.
 */
object ScrollToTop {
    /** The tap always targets the FIRST item - banners and pill rows included. */
    const val TOP_INDEX = 0

    /**
     * Already at the very top (first item fully in view)? Then the list has
     * nothing to do and only the collapsed bar needs re-expanding.
     */
    fun isAtTop(
        firstVisibleItemIndex: Int,
        firstVisibleItemScrollOffset: Int,
    ): Boolean = firstVisibleItemIndex == TOP_INDEX && firstVisibleItemScrollOffset == 0
}

/**
 * Pure decisions for what may share the title LINE of a collapsing
 * [androidx.compose.material3.LargeTopAppBar].
 *
 * Material's two-row bar composes the SAME `title` slot twice: once in the
 * always-visible collapsed row (small text style, fading IN with the
 * collapsed fraction) and once in the expanded row (headline text style,
 * fading OUT with `1 - collapsedFraction` and clipped away as the bar
 * shrinks). Anything that sits beside the title but must vanish when the
 * bar collapses therefore needs two answers, both taken from the bar's own
 * state rather than from a scroll offset of our own:
 *
 *  1. [isExpandedRow] - is this composition the expanded row? The bar tells
 *     the slot which row it is in through the text style it provides
 *     (LargeTopAppBar's documented contract: the expanded title is composed
 *     with a larger style than the collapsed one), so the expanded row is
 *     the one whose style is larger than the collapsed row's `titleLarge`.
 *     Without this the affordance would ALSO be composed - invisible but
 *     still tappable - inside the collapsed row while the bar is expanded.
 *  2. [showsExpandedAffordance] - has the bar handed over to the collapsed
 *     row? Material switches the title's semantics from the expanded to the
 *     collapsed row at `collapsedFraction < 0.5f` (TwoRowsTopAppBar's
 *     `hideTopRowSemantics`); the affordance leaves at exactly that point,
 *     so it and the title can never disagree about which row is live.
 */
object TitleCollapse {
    /** Material's own handover point between the expanded and collapsed title rows. */
    const val HANDOVER_FRACTION = 0.5f

    /** Shown while the expanded row is the live one; gone once the bar has handed over. */
    fun showsExpandedAffordance(collapsedFraction: Float): Boolean = collapsedFraction < HANDOVER_FRACTION

    /** True in the expanded row (headline style), false in the collapsed row (`titleLarge`). */
    fun isExpandedRow(
        rowStyle: TextStyle,
        typography: Typography,
    ): Boolean {
        val row = rowStyle.fontSize
        val collapsed = typography.titleLarge.fontSize
        return row.isSp && collapsed.isSp && row.value > collapsed.value
    }
}

/**
 * The ONE "Clear SMS" title shared by every top-level tab's app bar (Inbox,
 * Finance, Alerts), so the three cannot drift: same text, same tap-to-top
 * behaviour, same accessibility action. Tapping the title animates
 * [listState] back to its first item AND re-expands the collapsing bar
 * (a programmatic scroll bypasses the nested-scroll connection the bar
 * listens to, so the bar is animated open explicitly). Only the title text
 * is clickable - the bar's action icons beside it are untouched - and the
 * click carries an accessibility label so a screen reader announces the
 * gesture instead of leaving it undiscoverable.
 *
 * [expandedTrailing] is optional content placed at the END of the title line
 * in the EXPANDED row only, hidden by [TitleCollapse] as the bar collapses.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScrollToTopTitle(
    scrollBehavior: TopAppBarScrollBehavior,
    listState: LazyListState,
    expandedTrailing: (@Composable () -> Unit)? = null,
) {
    val scope = rememberCoroutineScope()
    val expandedRow = TitleCollapse.isExpandedRow(LocalTextStyle.current, MaterialTheme.typography)
    val showTrailing =
        expandedTrailing != null &&
            expandedRow &&
            TitleCollapse.showsExpandedAffordance(scrollBehavior.state.collapsedFraction)
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = stringResource(R.string.app_name),
            modifier =
                Modifier
                    .minimumInteractiveComponentSize()
                    .clickable(
                        interactionSource = null,
                        indication = null,
                        onClickLabel = stringResource(R.string.action_scroll_to_top),
                        role = Role.Button,
                    ) {
                        scope.launch { scrollToTop(listState, scrollBehavior.state) }
                    },
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (showTrailing) {
            Spacer(Modifier.weight(1f))
            // 12dp + the slot's own 4dp = the 16dp edge the action icons keep.
            Row(Modifier.padding(end = 12.dp)) { expandedTrailing?.invoke() }
        }
    }
}

/**
 * A genuine scroll to the first item plus an explicit re-expansion of the
 * bar, run together so the two arrive in step.
 */
@OptIn(ExperimentalMaterial3Api::class)
suspend fun scrollToTop(
    listState: LazyListState,
    bar: TopAppBarState,
) = coroutineScope {
    launch {
        if (!ScrollToTop.isAtTop(listState.firstVisibleItemIndex, listState.firstVisibleItemScrollOffset)) {
            listState.animateScrollToItem(ScrollToTop.TOP_INDEX)
        }
    }
    launch {
        animate(initialValue = bar.heightOffset, targetValue = 0f) { value, _ -> bar.heightOffset = value }
        bar.contentOffset = 0f
    }
}
