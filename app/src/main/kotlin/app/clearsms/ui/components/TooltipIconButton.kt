package app.clearsms.ui.components

import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.PlainTooltip
import androidx.compose.material3.Text
import androidx.compose.material3.TooltipBox
import androidx.compose.material3.TooltipDefaults
import androidx.compose.material3.rememberTooltipState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector

/**
 * The app's icon-only button: standard Android behaviour is that
 * long-pressing an icon button reveals its label, so every icon-only
 * top-bar / selection-bar / action button renders through this wrapper -
 * a Material3 [TooltipBox] with a [PlainTooltip] around a plain
 * [IconButton].
 *
 * ONE [label] feeds BOTH the tooltip text and the icon's
 * contentDescription: the string TalkBack reads and the string sighted
 * users long-press to see can never diverge.
 *
 * Deliberately NOT used where the button already carries a real long-press
 * action (the compose bar's Send button long-presses to schedule): the
 * tooltip's long-press would swallow it. Such buttons keep their own
 * affordances and are allowlisted in TooltipIconButtonConventionTest.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TooltipIconButton(
    label: String,
    onClick: () -> Unit,
    icon: ImageVector,
    modifier: Modifier = Modifier,
    tint: Color? = null,
) {
    TooltipBox(
        positionProvider = TooltipDefaults.rememberPlainTooltipPositionProvider(),
        tooltip = { PlainTooltip { Text(label) } },
        state = rememberTooltipState(),
    ) {
        IconButton(onClick = onClick, modifier = modifier) {
            // The same label is the accessibility description - single source.
            Icon(icon, contentDescription = label, tint = tint ?: LocalContentColor.current)
        }
    }
}
