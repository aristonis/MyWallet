package org.aristonis.mywallet.ui.reports.charts

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.unit.LayoutDirection
import org.aristonis.mywallet.ui.text
import org.aristonis.mywallet.ui.theme.WalletTheme

/**
 * One bar per category that is a positive spend, in the table's order, each under its category name.
 * A bar's length is its share of the longest bar ([SpendingBar.length]); no percentage is shown. A
 * category whose net amount is zero or a refund effect is left out here: the table lists it with its
 * sign, and a bar would claim a share that is not there.
 *
 * Bars start at the reading start, so they grow leftward in a right-to-left layout. The chart is one
 * image node for a screen reader, described by [description]; the table below is the exact alternative.
 * The bar thickness has no chart token; it uses the related-items gap.
 */
@Composable
internal fun SpendingBarChart(bars: List<SpendingBar>, description: String, modifier: Modifier = Modifier) {
    val dimensions = WalletTheme.dimensions
    val barColor = MaterialTheme.colorScheme.primary
    val trackColor = MaterialTheme.colorScheme.surfaceContainerHighest
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clearAndSetSemantics {
                contentDescription = description
                role = Role.Image
            },
        verticalArrangement = Arrangement.spacedBy(dimensions.relatedGap),
    ) {
        bars.filter { it.isDrawn }.forEach { bar ->
            Column(verticalArrangement = Arrangement.spacedBy(dimensions.grid)) {
                Text(bar.label.text(), style = MaterialTheme.typography.bodyMedium)
                Canvas(Modifier.fillMaxWidth().height(dimensions.relatedGap)) {
                    val corner = CornerRadius(size.height / 2)
                    drawRoundRect(trackColor, cornerRadius = corner)
                    val length = size.width * bar.length
                    val start = if (layoutDirection == LayoutDirection.Rtl) size.width - length else 0f
                    drawRoundRect(barColor, topLeft = Offset(start, 0f), size = Size(length, size.height), cornerRadius = corner)
                }
            }
        }
    }
}
