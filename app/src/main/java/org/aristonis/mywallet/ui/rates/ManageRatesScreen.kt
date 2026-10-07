package org.aristonis.mywallet.ui.rates

// The rate itself is shown as a plain decimal, not as money: it is a ratio, not an amount.

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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.aristonis.mywallet.R
import org.aristonis.mywallet.ui.message.text
import org.aristonis.mywallet.ui.components.EmptyState
import org.aristonis.mywallet.ui.components.WalletTopAppBar
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
internal fun ManageRatesContent(
    state: ManageRatesUiState,
    onInputChanged: (String, String) -> Unit,
    onSave: (String) -> Unit,
    onDone: () -> Unit,
) {
    Scaffold(
        topBar = {
            WalletTopAppBar(title = stringResource(R.string.rates_title), onBack = onDone)
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .padding(innerPadding)
                .padding(16.dp)
                .fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            val base = state.baseCurrencyCode
            when {
                state.isLoading -> CircularProgressIndicator()

                state.rows.isEmpty() -> EmptyState(
                    message = stringResource(R.string.rates_not_needed),
                )

                else -> {
                    base?.let {
                        Text(stringResource(R.string.rates_hint, it), style = MaterialTheme.typography.bodySmall)
                    }
                    LazyColumn(
                    // Without a bound the list asks for the height of all its rows, pushing the
                    // last of them past the bottom of the screen where no scroll can reach them.
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
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
            Text(
                stringResource(R.string.rates_currency_heading, row.currencyCode, row.symbol),
                style = MaterialTheme.typography.titleMedium,
            )

            val current = row.currentRate
            Text(
                when {
                    current == null -> stringResource(R.string.rates_unset)
                    base != null ->
                        stringResource(R.string.rates_current_in_base, row.currencyCode, current.toPlainString(), base)
                    else -> stringResource(R.string.rates_current, row.currencyCode, current.toPlainString())
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
                    label = {
                        Text(
                            base?.let { stringResource(R.string.field_rate_in_base, it) }
                                ?: stringResource(R.string.field_rate),
                        )
                    },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    isError = row.error != null,
                    modifier = Modifier.weight(1f),
                )
                Button(onClick = { onSave(row.currencyCode) }) {
                    Text(stringResource(R.string.action_save_rate))
                }
            }

            row.error?.let { message ->
                Text(
                    message.text(),
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                )
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
