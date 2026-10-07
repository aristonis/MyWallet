package org.aristonis.mywallet.ui.currency

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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.aristonis.mywallet.R
import org.aristonis.mywallet.ui.message.text
import org.aristonis.mywallet.domain.model.Currency
import org.aristonis.mywallet.ui.components.WalletTopAppBar
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

    Scaffold(
        topBar = {
            WalletTopAppBar(title = stringResource(R.string.base_currency_title), onBack = onDone)
        },
    ) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                stringResource(R.string.base_currency_body),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                stringResource(R.string.base_currency_current, state.currentBase),
                style = MaterialTheme.typography.titleMedium,
            )

            state.error?.let { message ->
                Text(
                    message.text(),
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodyMedium,
                )
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
                    label = {
                        Text(
                            stringResource(
                                R.string.base_currency_rate_label,
                                state.selectedCode,
                                state.currentBase,
                            ),
                        )
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            Button(
                onClick = { confirming = true },
                enabled = state.canApply,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    state.selectedCode?.let { stringResource(R.string.base_currency_switch_to, it) }
                        ?: stringResource(R.string.base_currency_choose),
                )
            }
        }
    }

    if (confirming && state.selectedCode != null) {
        AlertDialog(
            onDismissRequest = { confirming = false },
            title = { Text(stringResource(R.string.base_currency_confirm_title, state.selectedCode)) },
            text = {
                Text(stringResource(R.string.base_currency_confirm_body, state.selectedCode))
            },
            confirmButton = {
                TextButton(onClick = {
                    confirming = false
                    onApply()
                }) { Text(stringResource(R.string.action_switch)) }
            },
            dismissButton = {
                TextButton(onClick = { confirming = false }) { Text(stringResource(R.string.action_cancel)) }
            },
        )
    }
}

@Composable
private fun CurrencyRow(currency: Currency, isSelected: Boolean, onSelect: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                stringResource(R.string.currency_code_and_symbol, currency.code, currency.symbol),
                style = MaterialTheme.typography.bodyLarge,
            )
            TextButton(onClick = onSelect) {
                Text(stringResource(if (isSelected) R.string.action_selected else R.string.action_select))
            }
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
