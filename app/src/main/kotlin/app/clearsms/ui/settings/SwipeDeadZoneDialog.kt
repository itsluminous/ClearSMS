package app.clearsms.ui.settings

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import app.clearsms.R
import app.clearsms.domain.model.SwipeDeadZone
import kotlin.math.roundToInt

/**
 * Editor for the per-row swipe dead zone (issue #16): an enable switch, a
 * realistic three-row inbox preview with the zone shaded live on every row,
 * and Position / Width / Height sliders.
 *
 * The preview is honest by construction: the shaded rectangle is drawn from
 * [SwipeDeadZone.bounds] - the SAME geometry the swipe gesture consults
 * through [SwipeDeadZone.blocksTouchAt] - so what the user sees is exactly
 * where swipes will not start. Sliders (not drag handles) keep the feature
 * fully configurable without fine motor control; slider positions update the
 * overlay while dragging and persist when the drag ends.
 */
@Composable
internal fun SwipeDeadZoneDialog(
    value: SwipeDeadZone,
    onChange: (SwipeDeadZone) -> Unit,
    onDismiss: () -> Unit,
) {
    // Local copy so the overlay tracks the slider mid-drag; every committed
    // change (switch toggle, slider release) is persisted through onChange.
    var current by remember { mutableStateOf(value.sanitized()) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.settings_swipe_dead_zone)) },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = stringResource(R.string.settings_swipe_dead_zone_enable),
                        modifier = Modifier.weight(1f),
                    )
                    Switch(
                        checked = current.enabled,
                        onCheckedChange = {
                            current = current.copy(enabled = it)
                            onChange(current)
                        },
                    )
                }
                DeadZonePreview(zone = current)
                Text(
                    text = stringResource(R.string.settings_swipe_dead_zone_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                PercentSlider(
                    label = stringResource(R.string.settings_swipe_dead_zone_position),
                    value = current.centerXPercent,
                    valueRange = SwipeDeadZone.MIN_CENTER..SwipeDeadZone.MAX_CENTER,
                    enabled = current.enabled,
                    onValueChange = { current = current.copy(centerXPercent = it) },
                    onValueChangeFinished = { onChange(current) },
                )
                PercentSlider(
                    label = stringResource(R.string.settings_swipe_dead_zone_width),
                    value = current.widthPercent,
                    valueRange = SwipeDeadZone.MIN_WIDTH..SwipeDeadZone.MAX_WIDTH,
                    enabled = current.enabled,
                    onValueChange = { current = current.copy(widthPercent = it) },
                    onValueChangeFinished = { onChange(current) },
                )
                PercentSlider(
                    label = stringResource(R.string.settings_swipe_dead_zone_height),
                    value = current.heightPercent,
                    valueRange = SwipeDeadZone.MIN_HEIGHT..SwipeDeadZone.MAX_HEIGHT,
                    enabled = current.enabled,
                    onValueChange = { current = current.copy(heightPercent = it) },
                    onValueChangeFinished = { onChange(current) },
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_done)) }
        },
    )
}

/**
 * Three mock inbox rows with the dead zone shaded on each. The overlay
 * rectangle comes from [SwipeDeadZone.bounds] scaled to the row's size -
 * geometry is never re-derived here, so preview and gesture cannot drift
 * (pinned by SwipeDeadZoneGeometryConventionTest).
 */
@Composable
private fun DeadZonePreview(
    zone: SwipeDeadZone,
    modifier: Modifier = Modifier,
) {
    val overlayColor = MaterialTheme.colorScheme.primary
    val description = stringResource(R.string.settings_swipe_dead_zone_preview_description)
    Column(
        modifier =
            modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f))
                .semantics { contentDescription = description },
    ) {
        repeat(3) { index ->
            Box(Modifier.fillMaxWidth()) {
                MockInboxRow(index)
                val bounds = zone.bounds()
                if (bounds != null) {
                    Canvas(Modifier.matchParentSize()) {
                        drawRect(
                            color = overlayColor.copy(alpha = 0.30f),
                            topLeft = Offset(bounds.left * size.width, bounds.top * size.height),
                            size =
                                Size(
                                    width = (bounds.right - bounds.left) * size.width,
                                    height = (bounds.bottom - bounds.top) * size.height,
                                ),
                        )
                    }
                }
            }
        }
    }
}

/** A schematic inbox row: avatar circle plus two text-shaped bars. */
@Composable
private fun MockInboxRow(index: Int) {
    val barColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.35f)
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(36.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.secondaryContainer),
        )
        Spacer(Modifier.width(12.dp))
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Box(
                Modifier
                    .fillMaxWidth(fraction = 0.45f + index * 0.1f)
                    .height(10.dp)
                    .clip(RoundedCornerShape(5.dp))
                    .background(barColor),
            )
            Box(
                Modifier
                    .fillMaxWidth(fraction = 0.8f - index * 0.08f)
                    .height(8.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(barColor.copy(alpha = 0.25f)),
            )
        }
    }
}

/** Labelled 0-100% slider with a live value readout. */
@Composable
private fun PercentSlider(
    label: String,
    value: Int,
    valueRange: IntRange,
    enabled: Boolean,
    onValueChange: (Int) -> Unit,
    onValueChangeFinished: () -> Unit,
) {
    Column {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(text = label, style = MaterialTheme.typography.labelLarge)
            Text(
                text = stringResource(R.string.settings_swipe_dead_zone_percent, value),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Slider(
            value = value.toFloat(),
            onValueChange = { onValueChange(it.roundToInt()) },
            valueRange = valueRange.first.toFloat()..valueRange.last.toFloat(),
            enabled = enabled,
            onValueChangeFinished = onValueChangeFinished,
            modifier = Modifier.semantics { contentDescription = label },
        )
    }
}
