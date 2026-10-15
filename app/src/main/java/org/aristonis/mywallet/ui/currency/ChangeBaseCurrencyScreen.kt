package org.aristonis.mywallet.ui.currency

import android.content.res.Configuration
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.aristonis.mywallet.R
import org.aristonis.mywallet.domain.model.Currency
import org.aristonis.mywallet.ui.components.SectionHeader
import org.aristonis.mywallet.ui.components.WalletTopAppBar
import org.aristonis.mywallet.ui.message.text
import org.aristonis.mywallet.ui.theme.MyWalletTheme
import org.aristonis.mywallet.ui.theme.WalletTheme

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
internal fun ChangeBaseCurrencyContent(
    state: ChangeBaseCurrencyUiState,
    onSelect: (String) -> Unit,
    onRateChanged: (String) -> Unit,
    onApply: () -> Unit,
    onDone: () -> Unit,
) {
    var confirming by rememberSaveable { mutableStateOf(false) }
    val dimensions = WalletTheme.dimensions
    val listState = rememberLazyListState()
    LaunchedEffect(state.selectedCode, state.needsRate, state.error) {
        when {
            state.error != null -> listState.scrollToItem(1)
            state.needsRate && state.selectedCode != null -> listState.scrollToItem(2)
        }
    }

    Scaffold(
        topBar = {
            WalletTopAppBar(title = stringResource(R.string.base_currency_title), onBack = onDone)
        },
    ) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).padding(dimensions.screenPadding),
            verticalArrangement = Arrangement.spacedBy(dimensions.regionGap),
        ) {
            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(dimensions.relatedGap),
                state = listState,
                modifier = Modifier.weight(1f).testTag("base-currency-options"),
            ) {
                item(key = "current-base") {
                    Surface(
                        color = MaterialTheme.colorScheme.primaryContainer,
                        shape = MaterialTheme.shapes.medium,
                    ) {
                        Column(
                            modifier = Modifier.padding(dimensions.cardPadding),
                            verticalArrangement = Arrangement.spacedBy(dimensions.relatedGap),
                        ) {
                            Text(
                                stringResource(R.string.base_currency_current, state.currentBase),
                                style = MaterialTheme.typography.titleMedium,
                                color = MaterialTheme.colorScheme.onPrimaryContainer,
                            )
                            Text(
                                stringResource(R.string.base_currency_body),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onPrimaryContainer,
                            )
                        }
                    }
                }
                state.error?.let { message ->
                    item(key = "apply-error") {
                        Surface(
                            color = MaterialTheme.colorScheme.errorContainer,
                            shape = MaterialTheme.shapes.medium,
                        ) {
                            Text(
                                message.text(),
                                modifier = Modifier.padding(dimensions.cardPadding),
                                color = MaterialTheme.colorScheme.onErrorContainer,
                                style = MaterialTheme.typography.bodyMedium,
                            )
                        }
                    }
                }
                item(key = "choose-heading") {
                    SectionHeader(title = stringResource(R.string.w2_choose_currency))
                }
                if (state.needsRate && state.selectedCode != null) {
                    item(key = "required-rate") {
                        // A long list must not hide the rate required by the selected currency.
                        OutlinedTextField(
                            value = state.rateInput,
                            onValueChange = onRateChanged,
                            enabled = !state.isWorking,
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
                            modifier = Modifier.fillMaxWidth().testTag("base-required-rate"),
                        )
                    }
                }
                items(state.selectable, key = { it.code }) { currency ->
                    CurrencyRow(
                        currency = currency,
                        isSelected = currency.code == state.selectedCode,
                        enabled = !state.isWorking,
                        onSelect = { onSelect(currency.code) },
                    )
                }
            }

            Button(
                onClick = { confirming = true },
                enabled = state.canApply,
                modifier = Modifier.fillMaxWidth().heightIn(min = dimensions.minTouchTarget),
            ) {
                Text(
                    state.selectedCode?.let { stringResource(R.string.base_currency_switch_to, it) }
                        ?: stringResource(R.string.base_currency_choose),
                )
            }
        }
    }

    if (confirming && state.canApply && state.selectedCode != null) {
        AlertDialog(
            onDismissRequest = { confirming = false },
            title = { Text(stringResource(R.string.base_currency_confirm_title, state.selectedCode)) },
            text = {
                Text(stringResource(R.string.base_currency_confirm_body, state.selectedCode))
            },
            confirmButton = {
                TextButton(enabled = state.canApply, onClick = {
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
private fun CurrencyRow(
    currency: Currency,
    isSelected: Boolean,
    enabled: Boolean,
    onSelect: () -> Unit,
) {
    val dimensions = WalletTheme.dimensions
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer), border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().heightIn(min = dimensions.minTouchTarget)
                .padding(dimensions.cardPadding),
            horizontalArrangement = Arrangement.spacedBy(dimensions.relatedGap),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                stringResource(R.string.currency_code_and_symbol, currency.code, currency.symbol),
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.weight(1f),
            )
            TextButton(
                onClick = onSelect,
                enabled = enabled,
                modifier = Modifier.testTag("select-currency-${currency.code}"),
            ) {
                Text(stringResource(if (isSelected) R.string.action_selected else R.string.action_select))
            }
        }
    }
}

@Preview(name = "Base currency light", showBackground = true, widthDp = 360)
@Preview(name = "Base currency dark", showBackground = true, widthDp = 360,
    uiMode = Configuration.UI_MODE_NIGHT_YES)
@Preview(name = "Base currency large text", showBackground = true, widthDp = 360, fontScale = 2f)
@Composable
private fun ChangeBaseCurrencyPreview() {
    MyWalletTheme(dynamicColor = false) {
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
