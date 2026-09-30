package app.clearsms.ui.components

import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.NotificationsOff
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.clearsms.R

/**
 * THE muted-sender glyph: the visible reason a thread never notifies. Drawn
 * on the inbox row and beside the sender name in the conversation title
 * bar, from this ONE definition, so the icon and the words TalkBack reads
 * ([R.string.inbox_muted]) cannot drift between the two surfaces.
 *
 * The glyph is not decoration: a thread that is quiet with no visible cause
 * reads as "messages lost" (the lesson from the UNKNOWN category), so
 * wherever a muted thread is shown, this mark says why.
 *
 * Sized in dp, not sp, on purpose: it sits next to text that scales with
 * the font setting and must stay a small status mark rather than grow into
 * a second avatar at large font scales. Callers pick the size that fits
 * their row ([InboxRowSize] for the inbox metadata line, [TitleBarSize]
 * beside a title-sized name, where it matches the bar's action icons).
 */
object MutedIndicator {
    /** The one icon both surfaces draw. */
    val Icon: ImageVector = Icons.Outlined.NotificationsOff

    /** Matches the pinned glyph on the inbox row's metadata line. */
    val InboxRowSize: Dp = 14.dp

    /** Material's default `Icon` size, the size of every action glyph on the bar. */
    val ActionIconSize: Dp = 24.dp

    /**
     * The Material default icon size - what the Call and overflow glyphs
     * beside it in the top bar are drawn at ([androidx.compose.material3.Icon]
     * with no size modifier). At 18dp the bell read as an afterthought next
     * to a 24dp phone; matching the neighbours makes it part of the bar. It
     * stays an indicator: no click target, no ripple, same label.
     * (Declared after [ActionIconSize]: object initialisers run in order.)
     */
    val TitleBarSize: Dp = ActionIconSize
}

/** Draws the shared muted glyph; see [MutedIndicator]. */
@Composable
fun MutedIndicatorIcon(
    size: Dp,
    modifier: Modifier = Modifier,
) {
    Icon(
        MutedIndicator.Icon,
        contentDescription = stringResource(R.string.inbox_muted),
        tint = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier.size(size),
    )
}
