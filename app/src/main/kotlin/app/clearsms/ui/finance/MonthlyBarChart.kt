package app.clearsms.ui.finance

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

/**
 * Hand-rolled Compose Canvas bar chart: paired debit/credit bars per month,
 * animated growth on first display. No chart library.
 */
@Composable
fun MonthlyBarChart(
    data: List<MonthlyTotals>,
    modifier: Modifier = Modifier,
) {
    val debitColor = MaterialTheme.colorScheme.error
    val creditColor = MaterialTheme.colorScheme.tertiary
    val gridColor = MaterialTheme.colorScheme.outlineVariant

    var started by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { started = true }
    val growth by animateFloatAsState(
        targetValue = if (started) 1f else 0f,
        animationSpec = tween(durationMillis = 700),
        label = "bar_growth",
    )

    val maxValue = data.maxOfOrNull { maxOf(it.debits, it.credits) }?.takeIf { it > 0 } ?: 1.0

    Column(modifier = modifier.fillMaxWidth()) {
        Canvas(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .height(160.dp)
                    .padding(horizontal = 8.dp),
        ) {
            if (data.isEmpty()) return@Canvas
            val groupWidth = size.width / data.size
            val barWidth = groupWidth * 0.28f
            val gap = groupWidth * 0.08f

            // Baseline + quarter grid lines.
            for (line in 0..3) {
                val y = size.height * line / 3f
                drawLine(
                    color = gridColor,
                    start = Offset(0f, y),
                    end = Offset(size.width, y),
                    strokeWidth = 1.dp.toPx(),
                )
            }

            data.forEachIndexed { index, month ->
                val centerX = groupWidth * index + groupWidth / 2f
                val debitHeight = (month.debits / maxValue * size.height * growth).toFloat()
                val creditHeight = (month.credits / maxValue * size.height * growth).toFloat()
                drawRoundRect(
                    color = debitColor,
                    topLeft = Offset(centerX - barWidth - gap / 2f, size.height - debitHeight),
                    size = Size(barWidth, debitHeight),
                    cornerRadius =
                        androidx.compose.ui.geometry
                            .CornerRadius(4.dp.toPx()),
                )
                drawRoundRect(
                    color = creditColor,
                    topLeft = Offset(centerX + gap / 2f, size.height - creditHeight),
                    size = Size(barWidth, creditHeight),
                    cornerRadius =
                        androidx.compose.ui.geometry
                            .CornerRadius(4.dp.toPx()),
                )
            }
        }
        Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp)) {
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
}
