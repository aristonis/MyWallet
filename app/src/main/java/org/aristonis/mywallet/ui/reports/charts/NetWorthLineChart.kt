package org.aristonis.mywallet.ui.reports.charts

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.LayoutDirection
import org.aristonis.mywallet.ui.theme.WalletTheme

/**
 * Width over height of the line chart. The theme has no chart dimension, so the height follows the
 * width instead of a new dp value.
 */
private const val LINE_CHART_ASPECT_RATIO = 2f

/**
 * The most field heights the line may take on a wide screen, so a tablet or landscape window does not
 * get a line taller than the phone's viewport. Derived from a theme dimension because the theme has no
 * chart height.
 */
private const val MAX_HEIGHT_IN_FIELDS = 4

/** The line and its point markers, as a fraction of the grid unit. */
private const val STROKE_GRID_FRACTION = 0.5f

/** The visible text around the line, already formatted by the screen from view-model values. */
internal data class LineChartLabels(val highest: String, val lowest: String, val start: String, val end: String)

/**
 * The restated balances as a line, oldest first. Drawn only for a trend, from the same [points] the
 * exact table lists. Points sit by their day offset, so monthly points with a short last segment are
 * not stretched into equal steps. A series that crosses zero gets a zero line in the outline role.
 *
 * The highest and lowest values sit above and below the line, the first and last dates under it.
 * Earlier dates sit at the reading start: on the left, or on the right in a right-to-left layout.
 * The whole chart is one image node for a screen reader, described by [description]; the table below
 * is the full, exact alternative.
 */
@Composable
internal fun NetWorthLineChart(
    points: List<NetWorthPointRow>,
    labels: LineChartLabels,
    description: String,
    modifier: Modifier = Modifier,
) {
    val dimensions = WalletTheme.dimensions
    val labelStyle = MaterialTheme.typography.labelMedium
    val labelColor = MaterialTheme.colorScheme.onSurfaceVariant
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clearAndSetSemantics {
                contentDescription = description
                role = Role.Image
            },
        verticalArrangement = Arrangement.spacedBy(dimensions.grid),
    ) {
        Text(labels.highest, style = labelStyle, color = labelColor)
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            val height = minOf(maxWidth / LINE_CHART_ASPECT_RATIO, dimensions.fieldHeight * MAX_HEIGHT_IN_FIELDS)
            LineCanvas(points, Modifier.fillMaxWidth().height(height))
        }
        Text(labels.lowest, style = labelStyle, color = labelColor)
        Row(horizontalArrangement = Arrangement.spacedBy(dimensions.relatedGap)) {
            Text(labels.start, style = labelStyle, color = labelColor, modifier = Modifier.weight(1f))
            Text(labels.end, style = labelStyle, color = labelColor, textAlign = TextAlign.End, modifier = Modifier.weight(1f))
        }
    }
}

@Composable
private fun LineCanvas(points: List<NetWorthPointRow>, modifier: Modifier) {
    val lineColor = MaterialTheme.colorScheme.primary
    val zeroColor = MaterialTheme.colorScheme.outline
    val strokeWidth = WalletTheme.dimensions.grid * STROKE_GRID_FRACTION
    val values = remember(points) { points.map { it.balance } }
    val heights = remember(values) { ChartGeometry.lineHeights(values) }
    val xs = remember(points) { ChartGeometry.lineXs(points.map { it.date }) }
    val zero = remember(values) { ChartGeometry.zeroLine(values) }
    Canvas(modifier) {
        val stroke = strokeWidth.toPx()
        // Inset by a marker's reach so the first and last points are not cut by the canvas edge.
        val inset = stroke * 2
        val width = size.width - inset * 2
        val height = size.height - inset * 2
        val isRtl = layoutDirection == LayoutDirection.Rtl
        fun xAt(fraction: Float) = inset + width * (if (isRtl) 1f - fraction else fraction)
        fun yAt(fraction: Float) = inset + height * (1f - fraction)

        zero?.let { level ->
            drawLine(zeroColor, Offset(inset, yAt(level)), Offset(inset + width, yAt(level)), strokeWidth = stroke)
        }
        val offsets = xs.zip(heights) { x, y -> Offset(xAt(x), yAt(y)) }
        val path = Path().apply {
            offsets.forEachIndexed { index, point -> if (index == 0) moveTo(point.x, point.y) else lineTo(point.x, point.y) }
        }
        drawPath(path, lineColor, style = Stroke(width = stroke, cap = StrokeCap.Round, join = StrokeJoin.Round))
        offsets.forEach { point -> drawCircle(lineColor, radius = stroke, center = point) }
    }
}
