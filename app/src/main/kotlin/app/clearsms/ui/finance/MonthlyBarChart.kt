package app.clearsms.ui.finance

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.clearsms.R
import app.clearsms.ui.common.CurrencyFormat
import app.clearsms.ui.theme.LocalSemanticAmountColors

private val CHART_HEIGHT = 160.dp
private val AXIS_WIDTH = 48.dp
private const val GRID_STEPS = 3
private const val DIMMED_BAR_ALPHA = 0.35f

/**
 * Hand-rolled Compose Canvas bar chart: paired debit/credit bars per month
 * with a compact-INR y-axis, faint gridlines, a color key and tappable bars
 * that reveal the month's exact totals. No chart library.
 */
@Composable
fun MonthlyBarChart(
    data: List<MonthlyTotals>,
    modifier: Modifier = Modifier,
) {
    // Fixed semantic colors (not colorScheme): debit bars must stay red and
    // credit bars green whatever the wallpaper-derived palette looks like.
    val debitColor = LocalSemanticAmountColors.current.debit
    val creditColor = LocalSemanticAmountColors.current.credit
    val gridColor = MaterialTheme.colorScheme.outlineVariant

    var started by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { started = true }
    val growth by animateFloatAsState(
        targetValue = if (started) 1f else 0f,
        animationSpec = tween(durationMillis = 700),
        label = "bar_growth",
    )

    var selectedIndex by rememberSaveable(data.size) { mutableIntStateOf(-1) }

    fun toggleSelection(index: Int) {
        selectedIndex = if (selectedIndex == index) -1 else index
    }

    // Buckets arrive pre-aggregated from the ViewModel; only cheap scaling happens here.
    val maxValue = remember(data) { ChartMath.maxValue(data) }
    val chartSummary =
        if (data.isEmpty()) {
            stringResource(R.string.chart_empty_description)
        } else {
            stringResource(R.string.chart_summary_description, data.first().fullLabel, data.last().fullLabel)
        }

    Column(modifier = modifier.fillMaxWidth()) {
        SelectionDetailsRow(selected = data.getOrNull(selectedIndex))
        Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp)) {
            AxisLabels(maxValue = maxValue)
            Box(
                modifier =
                    Modifier
                        .weight(1f)
                        .height(CHART_HEIGHT)
                        .semantics { contentDescription = chartSummary },
            ) {
                Canvas(
                    modifier =
                        Modifier
                            .fillMaxSize()
                            .pointerInput(data) {
                                detectTapGestures { offset ->
                                    ChartMath
                                        .monthIndex(offset.x, size.width.toFloat(), data.size)
                                        ?.let(::toggleSelection)
                                }
                            },
                ) {
                    // Faint horizontal gridlines matching the axis labels.
                    val gridStroke = 1.dp.toPx()
                    for (line in 0..GRID_STEPS) {
                        val y = size.height * line / GRID_STEPS
                        drawLine(
                            color = gridColor,
                            start = Offset(0f, y),
                            end = Offset(size.width, y),
                            strokeWidth = gridStroke,
                        )
                    }
                    if (data.isEmpty()) return@Canvas

                    val groupWidth = size.width / data.size
                    val barWidth = groupWidth * 0.28f
                    val gap = groupWidth * 0.08f
                    val corner = CornerRadius(4.dp.toPx())
                    val hasSelection = selectedIndex in data.indices

                    if (hasSelection) {
                        // Tonal highlight behind the selected month's pair.
                        drawRoundRect(
                            color = gridColor.copy(alpha = 0.5f),
                            topLeft = Offset(groupWidth * selectedIndex, 0f),
                            size = Size(groupWidth, size.height),
                            cornerRadius = corner,
                        )
                    }

                    data.forEachIndexed { index, month ->
                        val alpha = if (!hasSelection || index == selectedIndex) 1f else DIMMED_BAR_ALPHA
                        val centerX = groupWidth * index + groupWidth / 2f
                        val debitHeight = (month.debits / maxValue * size.height * growth).toFloat()
                        val creditHeight = (month.credits / maxValue * size.height * growth).toFloat()
                        drawRoundRect(
                            color = debitColor.copy(alpha = alpha),
                            topLeft = Offset(centerX - barWidth - gap / 2f, size.height - debitHeight),
                            size = Size(barWidth, debitHeight),
                            cornerRadius = corner,
                        )
                        drawRoundRect(
                            color = creditColor.copy(alpha = alpha),
                            topLeft = Offset(centerX + gap / 2f, size.height - creditHeight),
                            size = Size(barWidth, creditHeight),
                            cornerRadius = corner,
                        )
                    }
                }
                // One focusable, labelled semantics node per bar group for TalkBack;
                // pointer events still reach the Canvas underneath.
                Row(modifier = Modifier.fillMaxSize()) {
                    data.forEachIndexed { index, month ->
                        val barLabel =
                            stringResource(
                                R.string.chart_bar_description,
                                month.fullLabel,
                                CurrencyFormat.rupees(month.debits),
                                CurrencyFormat.rupees(month.credits),
                            )
                        Box(
                            modifier =
                                Modifier
                                    .weight(1f)
                                    .fillMaxSize()
                                    .semantics {
                                        contentDescription = barLabel
                                        onClick {
                                            toggleSelection(index)
                                            true
                                        }
                                    },
                        )
                    }
                }
            }
        }
        if (data.isNotEmpty()) {
            Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp)) {
                Spacer(Modifier.width(AXIS_WIDTH))
                data.forEach { month ->
                    Text(
                        text = month.label,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
        ColorKey(debitColor = debitColor, creditColor = creditColor)
    }
}

@Composable
private fun AxisLabels(maxValue: Double) {
    Column(
        modifier = Modifier.width(AXIS_WIDTH).height(CHART_HEIGHT),
        verticalArrangement = Arrangement.SpaceBetween,
        horizontalAlignment = Alignment.End,
    ) {
        for (line in GRID_STEPS downTo 0) {
            Text(
                text = CompactInr.format(maxValue * line / GRID_STEPS),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(end = 8.dp),
            )
        }
    }
}

@Composable
private fun SelectionDetailsRow(selected: MonthlyTotals?) {
    AnimatedVisibility(visible = selected != null) {
        // Remember the last non-null value so the exit animation has content.
        var shown by remember { mutableStateOf(selected) }
        if (selected != null) shown = selected
        shown?.let { month ->
            Surface(
                shape = MaterialTheme.shapes.small,
                color = MaterialTheme.colorScheme.secondaryContainer,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            ) {
                Text(
                    text =
                        stringResource(
                            R.string.chart_selection_details,
                            month.fullLabel,
                            CurrencyFormat.rupees(month.debits),
                            CurrencyFormat.rupees(month.credits),
                        ),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                )
            }
        }
    }
}

@Composable
private fun ColorKey(
    debitColor: Color,
    creditColor: Color,
) {
    Row(
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        KeyEntry(color = debitColor, label = stringResource(R.string.chart_legend_debits))
        KeyEntry(color = creditColor, label = stringResource(R.string.chart_legend_credits))
    }
}

@Composable
private fun KeyEntry(
    color: Color,
    label: String,
) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Box(modifier = Modifier.size(8.dp).background(color = color, shape = CircleShape))
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
