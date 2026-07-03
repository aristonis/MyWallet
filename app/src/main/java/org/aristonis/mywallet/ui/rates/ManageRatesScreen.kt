package org.aristonis.mywallet.ui.rates

// UI copy hardcoded; localizing strings (RTL/i18n) comes later. Rate shown as a plain decimal.

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.aristonis.mywallet.ui.theme.MyWalletTheme
import java.math.BigDecimal

/**
 * Manage-rates screen. Lists the currencies the user's accounts use (base excluded) and lets each be
 * given a rate to the base currency. [onDone] returns to Home (Done button and system back). No nav
 * library: the parent toggles this screen, same as add-account.
 */
@Composable
fun ManageRatesScreen(
    onDone: () -> Unit,
    viewModel: ManageRatesViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    BackHandler(onBack = onDone)
    ManageRatesContent(
        state = state,
        onInputChanged = viewModel::setRateInput,
        onSave = viewModel::submit,
        onDone = onDone,
    )
}

@Composable
private fun ManageRatesContent(
    state: ManageRatesUiState,
    onInputChanged: (String, String) -> Unit,
    onSave: (String) -> Unit,
    onDone: () -> Unit,
) {
    Scaffold { innerPadding ->
        Column(
            modifier = Modifier
                .padding(innerPadding)
                .padding(24.dp)
                .fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Exchange rates", style = MaterialTheme.typography.headlineMedium, modifier = Modifier.weight(1f))
                TextButton(onClick = onDone) { Text("Done") }
            }

            val base = state.baseCurrencyCode
            when {
                state.isLoading -> CircularProgressIndicator()

                state.rows.isEmpty() -> Text(
                    "All your accounts are already in your base currency — no rates needed.",
                    style = MaterialTheme.typography.bodyMedium,
                )

                else -> {
                    base?.let {
                        Text("Enter how much 1 unit is worth in $it.", style = MaterialTheme.typography.bodySmall)
                    }
                    LazyColumn(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        items(state.rows, key = { it.currencyCode }) { row ->
                            RateRowCard(row = row, base = base, onInputChanged = onInputChanged, onSave = onSave)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun RateRowCard(
    row: RateRow,
    base: String?,
    onInputChanged: (String, String) -> Unit,
    onSave: (String) -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text("${row.currencyCode} (${row.symbol})", style = MaterialTheme.typography.titleMedium)

            val current = row.currentRate
            Text(
                if (current != null) {
                    "1 ${row.currencyCode} = ${current.toPlainString()}${base?.let { " $it" } ?: ""}"
                } else {
                    "No rate set yet"
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedTextField(
                    value = row.input,
                    onValueChange = { onInputChanged(row.currencyCode, it) },
                    label = { Text(base?.let { "Rate in $it" } ?: "Rate") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    isError = row.error != null,
                    modifier = Modifier.weight(1f),
                )
                Button(onClick = { onSave(row.currencyCode) }) { Text("Save") }
            }

            row.error?.let { message ->
                Text(message, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun ManageRatesPreview() {
    MyWalletTheme(dynamicColor = false) {
        ManageRatesContent(
            state = ManageRatesUiState(
                baseCurrencyCode = "USD",
                isLoading = false,
                rows = listOf(
                    RateRow("EUR", "€", currentRate = BigDecimal("1.10"), input = "1.10"),
                    RateRow("JPY", "¥", currentRate = null, input = ""),
                ),
            ),
            onInputChanged = { _, _ -> },
            onSave = {},
            onDone = {},
        )
    }
}
