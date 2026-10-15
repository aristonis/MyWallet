package org.aristonis.mywallet.ui.transaction

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextFieldColors
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import org.aristonis.mywallet.ui.theme.WalletTheme

private val TRANSACTION_FIELD_BORDER_WIDTH = 1.dp

/** A two-line filled choice that keeps long values and large text inside a full-width tap target. */
@Composable
internal fun TransactionFieldButton(
    label: String,
    value: String,
    onClick: () -> Unit,
    enabled: Boolean = true,
    trailing: (@Composable () -> Unit)? = null,
) {
    Surface(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier.fillMaxWidth().heightIn(min = WalletTheme.dimensions.fieldHeight),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
    ) {
        Row(
            modifier = Modifier.padding(
                horizontal = WalletTheme.dimensions.cardPadding,
                vertical = WalletTheme.dimensions.relatedGap,
            ),
            horizontalArrangement = Arrangement.spacedBy(WalletTheme.dimensions.relatedGap),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(WalletTheme.dimensions.grid),
            ) {
                Text(label, style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(value, style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface)
            }
            trailing?.invoke()
        }
    }
}

/** The existing short-list dropdown behavior with the transaction form's filled field surface. */
@Composable
internal fun <T> TransactionChoiceField(
    label: String,
    selectedText: String,
    items: List<T>,
    itemLabel: @Composable (T) -> String,
    onSelect: (T) -> Unit,
    enabled: Boolean = true,
) {
    var expanded by remember { mutableStateOf(false) }
    LaunchedEffect(enabled) { if (!enabled) expanded = false }
    Box {
        TransactionFieldButton(label, selectedText, onClick = { expanded = true }, enabled = enabled)
        DropdownMenu(expanded = expanded && enabled, onDismissRequest = { expanded = false }) {
            items.forEach { item ->
                DropdownMenuItem(text = { Text(itemLabel(item)) }, onClick = {
                    onSelect(item)
                    expanded = false
                })
            }
        }
    }
}

/** Groups the supporting transaction fields on one shared surface. */
@Composable
internal fun TransactionFieldsCard(content: @Composable ColumnScope.() -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceContainer,
        border = BorderStroke(TRANSACTION_FIELD_BORDER_WIDTH, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Column(
            modifier = Modifier.padding(WalletTheme.dimensions.cardPadding),
            verticalArrangement = Arrangement.spacedBy(WalletTheme.dimensions.relatedGap),
            content = content,
        )
    }
}

/** Gives legacy transaction controls the same elevated field container as the shared visuals. */
@Composable
internal fun TransactionFieldSurface(content: @Composable () -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
    ) {
        Column(
            modifier = Modifier.padding(WalletTheme.dimensions.relatedGap),
            verticalArrangement = Arrangement.spacedBy(WalletTheme.dimensions.relatedGap),
            content = { content() },
        )
    }
}

/** Filled Material fields stand out from the transaction card behind them. */
@Composable
internal fun transactionFieldColors(): TextFieldColors = TextFieldDefaults.colors(
    focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
    unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
    errorContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
    disabledContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
    focusedIndicatorColor = Color.Transparent,
    unfocusedIndicatorColor = Color.Transparent,
    disabledIndicatorColor = Color.Transparent,
    errorIndicatorColor = Color.Transparent,
)

/** Adds the transaction field's error outline without replacing its other modifiers. */
@Composable
internal fun Modifier.transactionErrorBorder(hasError: Boolean): Modifier =
    if (hasError) {
        border(TRANSACTION_FIELD_BORDER_WIDTH, MaterialTheme.colorScheme.error, MaterialTheme.shapes.medium)
    } else {
        this
    }
