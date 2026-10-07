package org.aristonis.mywallet.ui.onboarding

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
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
import org.aristonis.mywallet.domain.model.AccountTypeRegistry
import org.aristonis.mywallet.ui.account.accountTypeLabel
import org.aristonis.mywallet.ui.message.text
import org.aristonis.mywallet.domain.model.Currency
import org.aristonis.mywallet.ui.components.LabeledDropdown
import org.aristonis.mywallet.ui.components.SearchableCurrencyField
import org.aristonis.mywallet.ui.theme.MyWalletTheme

/**
 * First-run onboarding screen. Stateful wrapper: pulls the ViewModel via Hilt and turns its
 * [kotlinx.coroutines.flow.StateFlow] into Compose state with [collectAsStateWithLifecycle] (which
 * stops collecting when the UI is not visible). All logic lives in the ViewModel; this only renders
 * state and forwards events — so the body ([OnboardingContent]) is a pure, previewable function.
 */
@Composable
fun OnboardingScreen(viewModel: OnboardingViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    OnboardingContent(
        state = state,
        onCurrencySelected = viewModel::selectCurrency,
        onNameChanged = viewModel::setAccountName,
        onTypeSelected = viewModel::selectAccountType,
        onOpeningBalanceChanged = viewModel::setOpeningBalance,
        onSubmit = viewModel::submit,
    )
}

/** Stateless body — state in, events out (hoisted). No ViewModel reference, so previews work. */
@Composable
private fun OnboardingContent(
    state: OnboardingUiState,
    onCurrencySelected: (String) -> Unit,
    onNameChanged: (String) -> Unit,
    onTypeSelected: (String) -> Unit,
    onOpeningBalanceChanged: (String) -> Unit,
    onSubmit: () -> Unit,
) {
    Scaffold { innerPadding ->
        if (state.completed) {
            CompletedMessage(Modifier.padding(innerPadding).fillMaxSize())
            return@Scaffold
        }

        val selectedCurrency = state.currencies.firstOrNull { it.code == state.selectedCurrencyCode }
        val selectedType = AccountTypeRegistry.BuiltIns.all.firstOrNull { it.key == state.accountTypeKey }

        Column(
            // A first-run form of fixed-height fields; at 200% font scale the Finish button drops off
            // a compact screen entirely, which would leave the user unable to finish setting up.
            modifier = Modifier
                .padding(innerPadding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(stringResource(R.string.onboarding_title), style = MaterialTheme.typography.headlineMedium)
            Text(
                stringResource(R.string.onboarding_subtitle),
                style = MaterialTheme.typography.bodyMedium,
            )

            SearchableCurrencyField(
                label = stringResource(R.string.field_base_currency),
                selected = selectedCurrency,
                currencies = state.currencies,
                onSelect = onCurrencySelected,
            )

            OutlinedTextField(
                value = state.accountName,
                onValueChange = onNameChanged,
                label = { Text(stringResource(R.string.field_account_name)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )

            LabeledDropdown(
                label = stringResource(R.string.field_account_type),
                selectedText = selectedType?.let { accountTypeLabel(it.key) }
                    ?: stringResource(R.string.value_select_prompt),
                items = AccountTypeRegistry.BuiltIns.all,
                itemLabel = { accountTypeLabel(it.key) },
                onSelect = { onTypeSelected(it.key) },
            )

            OutlinedTextField(
                value = state.openingBalanceInput,
                onValueChange = onOpeningBalanceChanged,
                label = { Text(stringResource(R.string.field_opening_balance)) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                modifier = Modifier.fillMaxWidth(),
            )

            state.error?.let { message ->
                Text(
                    text = message.text(),
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }

            Spacer(Modifier.height(8.dp))

            Button(
                onClick = onSubmit,
                enabled = state.canSubmit,
                modifier = Modifier.fillMaxWidth(),
            ) {
                if (state.isSubmitting) {
                    CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                } else {
                    Text(stringResource(R.string.onboarding_finish))
                }
            }
        }
    }
}

@Composable
private fun CompletedMessage(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(stringResource(R.string.onboarding_complete_title), style = MaterialTheme.typography.headlineSmall)
        // Brief end state; the app routes to Home automatically once onboarding saves settings.
        Text(stringResource(R.string.onboarding_complete_body), style = MaterialTheme.typography.bodyMedium)
    }
}

@Preview(showBackground = true)
@Composable
private fun OnboardingPreview() {
    MyWalletTheme(dynamicColor = false) {
        OnboardingContent(
            state = OnboardingUiState(
                currencies = listOf(Currency("USD", "$", 2), Currency("EUR", "€", 2)),
                selectedCurrencyCode = "USD",
                accountName = "Cash",
            ),
            onCurrencySelected = {},
            onNameChanged = {},
            onTypeSelected = {},
            onOpeningBalanceChanged = {},
            onSubmit = {},
        )
    }
}
