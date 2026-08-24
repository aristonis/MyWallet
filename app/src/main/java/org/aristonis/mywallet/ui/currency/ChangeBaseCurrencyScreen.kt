package org.aristonis.mywallet.ui.currency

// UI copy hardcoded; localizing strings (RTL/i18n) comes later.

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.aristonis.mywallet.domain.model.Currency
import org.aristonis.mywallet.ui.theme.MyWalletTheme

/**
 * Changes the app's base currency.
 *
 * The confirmation is deliberately heavier than a settings toggle: this re-expresses every stored
 * rate through one freshly typed number and replaces the values the user originally entered, so it
 * cannot be undone. Recorded amounts are untouched — only what they are converted into changes.
 */
@Composable
fun ChangeBaseCurrencyScreen(
    onDone: () -> Unit,
    viewModel: ChangeBaseCurrencyViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    BackHandler(onBack = onDone)

    LaunchedEffect(state.applied) {
        if (state.applied) {
            viewModel.acknowledge()
            onDone()
        }
    }

    ChangeBaseCurrencyContent(
        state = state,
        onSelect = viewModel::select,
        onRateChanged = viewModel::setRateInput,
        onApply = viewModel::apply,
        onDone = onDone,
    )
}

@Composable
private fun ChangeBaseCurrencyContent(
    state: ChangeBaseCurrencyUiState,
    onSelect: (String) -> Unit,
    onRateChanged: (String) -> Unit,
    onApply: () -> Unit,
    onDone: () -> Unit,
) {
    var confirming by rememberSaveable { mutableStateOf(false) }

    Scaffold { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Base currency", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.weight(1f))
                TextButton(onClick = onDone) { Text("Done") }
            }
            Text(
                "Everything is totalled in this currency. Your recorded amounts stay exactly as they " +
                    "are — only what they convert into changes.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text("Currently ${state.currentBase}", style = MaterialTheme.typography.titleMedium)

            state.error?.let {
                Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
            }

            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.weight(1f)) {
                items(state.selectable, key = { it.code }) { currency ->
                    CurrencyRow(
                        currency = currency,
                        isSelected = currency.code == state.selectedCode,
                        onSelect = { onSelect(currency.code) },
                    )
                }
            }

            if (state.needsRate && state.selectedCode != null) {
                // Asked for here because Manage Rates only lists currencies an account already holds,
                // so a currency you do not own yet could never be given a rate there.
                OutlinedTextField(
                    value = state.rateInput,
                    onValueChange = onRateChanged,
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    label = { Text("1 ${state.selectedCode} in ${state.currentBase}") },
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            Button(
                onClick = { confirming = true },
                enabled = state.canApply,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(state.selectedCode?.let { "Switch to $it" } ?: "Choose a currency")
            }
        }
    }

    if (confirming && state.selectedCode != null) {
        AlertDialog(
            onDismissRequest = { confirming = false },
            title = { Text("Switch to ${state.selectedCode}?") },
            text = {
                Text(
                    "Every exchange rate will be re-expressed against ${state.selectedCode}, replacing " +
                        "the ones you entered. Your accounts and transactions are not changed. This " +
                        "cannot be undone.",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    confirming = false
                    onApply()
                }) { Text("Switch") }
            },
            dismissButton = { TextButton(onClick = { confirming = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun CurrencyRow(currency: Currency, isSelected: Boolean, onSelect: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("${currency.code} ${currency.symbol}", style = MaterialTheme.typography.bodyLarge)
            TextButton(onClick = onSelect) { Text(if (isSelected) "Selected" else "Select") }
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun ChangeBaseCurrencyPreview() {
    MyWalletTheme {
        ChangeBaseCurrencyContent(
            state = ChangeBaseCurrencyUiState(
                currentBase = "USD",
                selectable = listOf(Currency("EUR", "€", 2), Currency("GBP", "£", 2)),
                selectedCode = "GBP",
                needsRate = true,
            ),
            onSelect = {},
            onRateChanged = {},
            onApply = {},
            onDone = {},
        )
    }
}
