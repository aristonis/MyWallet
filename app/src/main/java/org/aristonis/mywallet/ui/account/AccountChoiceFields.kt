package org.aristonis.mywallet.ui.account

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import org.aristonis.mywallet.R
import org.aristonis.mywallet.domain.model.Currency
import org.aristonis.mywallet.ui.components.SearchableListDialog
import org.aristonis.mywallet.ui.icons.WalletIcons
import org.aristonis.mywallet.ui.theme.WalletTheme

@Composable
internal fun AccountChoiceSurface(label: String, value: String, enabled: Boolean, onClick: () -> Unit) {
    val dimensions = WalletTheme.dimensions
    Column(verticalArrangement = Arrangement.spacedBy(dimensions.grid)) {
        Text(label, style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row(
            modifier = Modifier.fillMaxWidth()
                .clip(MaterialTheme.shapes.medium)
                .background(MaterialTheme.colorScheme.surfaceContainer)
                .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
                .heightIn(min = dimensions.fieldHeight)
                .padding(horizontal = dimensions.cardPadding, vertical = dimensions.relatedGap),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(value, style = MaterialTheme.typography.bodyLarge,
                color = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f))
            Icon(WalletIcons.Expand, contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
internal fun <T> AccountDropdownField(
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
        AccountChoiceSurface(label, selectedText, enabled) { expanded = true }
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

@Composable
internal fun AccountCurrencyField(
    label: String,
    selected: Currency?,
    currencies: List<Currency>,
    onSelect: (String) -> Unit,
    enabled: Boolean = true,
) {
    var showingPicker by remember { mutableStateOf(false) }
    LaunchedEffect(enabled) { if (!enabled) showingPicker = false }
    AccountChoiceSurface(
        label,
        selected?.let { stringResource(R.string.account_with_currency, it.code, it.symbol) }
            ?: stringResource(R.string.value_select_prompt),
        enabled,
    ) { showingPicker = true }
    if (showingPicker && enabled) {
        SearchableListDialog(
            title = label,
            items = currencies,
            itemLabel = { stringResource(R.string.currency_code_with_symbol, it.code, it.symbol) },
            matches = { currency, query ->
                currency.code.contains(query, ignoreCase = true) || currency.symbol.contains(query, ignoreCase = true)
            },
            onPick = {
                onSelect(it.code)
                showingPicker = false
            },
            onDismiss = { showingPicker = false },
        )
    }
}
