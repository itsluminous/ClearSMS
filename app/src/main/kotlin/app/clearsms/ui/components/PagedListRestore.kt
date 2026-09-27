package app.clearsms.ui.components

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.paging.LoadState
import androidx.paging.compose.LazyPagingItems

/**
 * Keeping a paged `LazyColumn`'s scroll position across a round trip to
 * another screen.
 *
 * Two things conspire against it. Room invalidates the `PagingSource` on
 * every write (opening a thread marks it read), and Paging answers with a
 * fresh generation whose page flow only starts when something collects it -
 * so while the screen is gone NOTHING loads, and the first frame back sees
 * zero rows. When it does load, the refresh is anchored around the last
 * accessed row, i.e. a window that starts well BEFORE it. A `LazyListState`
 * restores an index (saved state has no key to look up), and a measure
 * against too few items clamps that index for good.
 *
 * The screens therefore (1) enable placeholders, so the index space is the
 * full result order whatever window is loaded, and null rows are drawn by
 * [PagedRowPlaceholder] at a row's height; and (2) skip composing the list
 * while [awaitingFirstPage] - only when there IS a position to protect, so
 * a cold start or a new query still shows its chrome immediately.
 */
@Composable
fun LazyListState.awaitingFirstPage(items: LazyPagingItems<*>): Boolean {
    // derivedStateOf: recompose the caller only when the answer flips, not
    // on every scrolled pixel.
    val scrolled by remember(this) {
        derivedStateOf { firstVisibleItemIndex > 0 || firstVisibleItemScrollOffset > 0 }
    }
    return scrolled && items.itemCount == 0 && items.loadState.refresh is LoadState.Loading
}

/**
 * Height a placeholder row reserves for a result that is not loaded yet:
 * the Material three-line list item rows are built from. A slightly-off
 * guess only nudges the viewport as real rows swap in - it never loses the
 * position, which a zero-height row would (the viewport would swallow
 * hundreds of them).
 */
val PAGED_ROW_PLACEHOLDER_HEIGHT: Dp = 88.dp

/** Blank stand-in for a not-yet-loaded paged row. */
@Composable
fun PagedRowPlaceholder(
    modifier: Modifier = Modifier,
    height: Dp = PAGED_ROW_PLACEHOLDER_HEIGHT,
) {
    Spacer(modifier.fillMaxWidth().height(height))
}
