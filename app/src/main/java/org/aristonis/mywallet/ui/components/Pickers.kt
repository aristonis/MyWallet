package org.aristonis.mywallet.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import org.aristonis.mywallet.R
import org.aristonis.mywallet.domain.model.Currency
import org.aristonis.mywallet.ui.icons.WalletIcons

/**
 * Reusable form pickers shared across screens (onboarding, add-account). Kept in a neutral package
 * so feature screens don't import UI widgets from each other. All stable Material 3 APIs — no
 * experimental ExposedDropdownMenuBox.
 */

/**
 * The caret on a field that opens a menu. It is decorative — the field's own label already tells a
 * screen reader what the control is, so announcing "show options" after it would only repeat.
 */
@Composable
private fun DropdownCaret() {
    Icon(imageVector = WalletIcons.Expand, contentDescription = null)
}

/** A labelled field that opens a searchable currency picker; only the chosen code goes back up. */
@Composable
internal fun SearchableCurrencyField(
    label: String,
    selected: Currency?,
    currencies: List<Currency>,
    onSelect: (String) -> Unit,
) {
    var showPicker by remember { mutableStateOf(false) }
    Column {
        Text(label, style = MaterialTheme.typography.labelMedium)
        OutlinedButton(onClick = { showPicker = true }, modifier = Modifier.fillMaxWidth()) {
            Text(
                text = selected?.let { stringResource(R.string.account_with_currency, it.code, it.symbol) }
                    ?: stringResource(R.string.value_select_prompt),
                modifier = Modifier.weight(1f),
            )
            DropdownCaret()
        }
    }
    if (showPicker) {
        SearchableListDialog(
            title = label,
            items = currencies,
            itemLabel = { stringResource(R.string.currency_code_with_symbol, it.code, it.symbol) },
            matches = { c, q -> c.code.contains(q, ignoreCase = true) || c.symbol.contains(q, ignoreCase = true) },
            onPick = {
                onSelect(it.code)
                showPicker = false
            },
            onDismiss = { showPicker = false },
        )
    }
}

/** A query box over a scrollable, filtered list. Search text is ephemeral, kept via `remember`. */
@Composable
internal fun <T> SearchableListDialog(
    title: String,
    items: List<T>,
    itemLabel: @Composable (T) -> String,
    matches: (T, String) -> Boolean,
    onPick: (T) -> Unit,
    onDismiss: () -> Unit,
) {
    var query by remember { mutableStateOf("") }
    val filtered = remember(query, items) {
        if (query.isBlank()) items else items.filter { matches(it, query) }
    }
    Dialog(onDismissRequest = onDismiss) {
        Surface(shape = MaterialTheme.shapes.large, tonalElevation = 6.dp) {
            Column(Modifier.fillMaxWidth().padding(16.dp)) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    placeholder = { Text(stringResource(R.string.action_search)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                )
                if (filtered.isEmpty()) {
                    Text(
                        stringResource(R.string.search_no_match, query),
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(vertical = 12.dp),
                    )
                } else {
                    LazyColumn(modifier = Modifier.fillMaxWidth().heightIn(max = 420.dp)) {
                        items(filtered) { item ->
                            Text(
                                text = itemLabel(item),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { onPick(item) }
                                    .padding(vertical = 12.dp),
                            )
                        }
                    }
                }
            }
        }
    }
}

/** A label + a full-width button that opens a [DropdownMenu]. Fine for short lists (account types). */
@Composable
internal fun <T> LabeledDropdown(
    label: String,
    selectedText: String,
    items: List<T>,
    itemLabel: @Composable (T) -> String,
    onSelect: (T) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Column {
        Text(label, style = MaterialTheme.typography.labelMedium)
        Box {
            OutlinedButton(onClick = { expanded = true }, modifier = Modifier.fillMaxWidth()) {
                Text(selectedText, modifier = Modifier.weight(1f))
                DropdownCaret()
            }
            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                items.forEach { item ->
                    DropdownMenuItem(
                        text = { Text(itemLabel(item)) },
                        onClick = {
                            onSelect(item)
                            expanded = false
                        },
                    )
                }
            }
        }
    }
}
