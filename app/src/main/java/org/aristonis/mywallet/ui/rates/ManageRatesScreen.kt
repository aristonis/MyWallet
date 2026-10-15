package org.aristonis.mywallet.ui.rates

// The rate itself is shown as a plain decimal, not as money: it is a ratio, not an amount.

import android.content.res.Configuration
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import java.math.BigDecimal
import org.aristonis.mywallet.R
import org.aristonis.mywallet.ui.components.EmptyState
import org.aristonis.mywallet.ui.components.LoadFailedState
import org.aristonis.mywallet.ui.components.SectionHeader
import org.aristonis.mywallet.ui.components.WalletTopAppBar
import org.aristonis.mywallet.ui.message.text
import org.aristonis.mywallet.ui.theme.MyWalletTheme
import org.aristonis.mywallet.ui.theme.WalletTheme

/**
 * Manage-rates screen. Lists currencies used by active accounts and outstanding debts (base excluded) and lets each be
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
        onRetry = viewModel::retry,
    )
}

@Composable
internal fun ManageRatesContent(
    state: ManageRatesUiState,
    onInputChanged: (String, String) -> Unit,
    onSave: (String) -> Unit,
    onDone: () -> Unit,
    onRetry: () -> Unit,
) {
    val dimensions = WalletTheme.dimensions
    Scaffold(
        topBar = {
            WalletTopAppBar(title = stringResource(R.string.rates_title), onBack = onDone)
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .padding(innerPadding)
                .padding(dimensions.screenPadding)
                .fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(dimensions.regionGap),
        ) {
            val base = state.baseCurrencyCode
            when {
                state.loadFailed -> LoadFailedState(stringResource(R.string.rates_data_unreadable), onRetry = onRetry)

                state.isLoading -> CircularProgressIndicator()

                state.rows.isEmpty() -> EmptyState(
                    message = stringResource(R.string.rates_not_needed),
                )

                else -> {
                    base?.let {
                        Surface(
                            color = MaterialTheme.colorScheme.surfaceContainer,
                            shape = MaterialTheme.shapes.medium,
                        ) {
                            Text(
                                stringResource(R.string.rates_hint, it),
                                modifier = Modifier.padding(dimensions.cardPadding),
                                style = MaterialTheme.typography.bodyMedium,
                            )
                        }
                    }
                    SectionHeader(title = stringResource(R.string.w2_rates_currencies))
                    LazyColumn(
                    // Without a bound the list asks for the height of all its rows, pushing the
                    // last of them past the bottom of the screen where no scroll can reach them.
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(dimensions.regionGap),
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
    val dimensions = WalletTheme.dimensions
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer), border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Column(
            modifier = Modifier.padding(dimensions.cardPadding),
            verticalArrangement = Arrangement.spacedBy(dimensions.relatedGap),
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
                color = if (current == null) WalletTheme.colors.warning
                    else MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(dimensions.relatedGap),
            ) {
                val rateLabel = base?.let { stringResource(R.string.field_rate_in_base, it) }
                    ?: stringResource(R.string.field_rate)
                Text(rateLabel, modifier = Modifier.fillMaxWidth(), style = MaterialTheme.typography.bodyMedium)
                OutlinedTextField(
                    value = row.input,
                    onValueChange = { onInputChanged(row.currencyCode, it) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    isError = row.error != null,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("rate-input-${row.currencyCode}")
                        .semantics { contentDescription = rateLabel },
                )
                row.error?.let { message ->
                    Text(
                        message.text(),
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                Button(
                    onClick = { onSave(row.currencyCode) },
                    modifier = Modifier.fillMaxWidth().heightIn(min = dimensions.minTouchTarget),
                ) {
                    Text(
                        stringResource(R.string.action_save_rate),
                        modifier = Modifier.fillMaxWidth(),
                        textAlign = TextAlign.Center,
                    )
                }
            }

        }
    }
}

@Preview(name = "Rates light", showBackground = true, widthDp = 360)
@Preview(name = "Rates dark", showBackground = true, widthDp = 360,
    uiMode = Configuration.UI_MODE_NIGHT_YES)
@Preview(name = "Rates large text", showBackground = true, widthDp = 360, fontScale = 2f)
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
            onRetry = {},
        )
    }
}
