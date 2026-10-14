package org.aristonis.mywallet.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.LayoutDirection
import org.aristonis.mywallet.ui.theme.WalletTheme

/**
 * A labelled figure on its own surface. A missing [value] shows [missingText] in words, never a zero
 * amount. [valueColor] is for a figure whose sign carries meaning (income, expense).
 */
@Composable
fun MetricCell(
    label: String,
    value: String?,
    missingText: String,
    modifier: Modifier = Modifier,
    valueColor: Color = Color.Unspecified,
) {
    val dimensions = WalletTheme.dimensions
    Card(
        modifier = modifier,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(dimensions.cardPadding),
            verticalArrangement = Arrangement.spacedBy(dimensions.grid),
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (value != null) {
                // The figure gets the whole line: a tight intrinsic width can clip glyph overhang at large
                // text. MoneyText draws left-to-right, so the side is chosen here to follow the label.
                val side = if (LocalLayoutDirection.current == LayoutDirection.Rtl) TextAlign.Right else TextAlign.Left
                MoneyText(
                    amount = value,
                    modifier = Modifier.fillMaxWidth(),
                    style = MaterialTheme.typography.titleMedium.copy(textAlign = side),
                    color = valueColor,
                )
            } else {
                Text(text = missingText, style = MaterialTheme.typography.titleMedium)
            }
        }
    }
}
