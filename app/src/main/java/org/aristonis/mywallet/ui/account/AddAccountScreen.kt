package org.aristonis.mywallet.ui.account

import androidx.activity.compose.BackHandler
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.aristonis.mywallet.R
import org.aristonis.mywallet.domain.model.AccountTypeRegistry
import org.aristonis.mywallet.ui.message.text
import org.aristonis.mywallet.domain.model.Currency
import org.aristonis.mywallet.ui.components.LabeledDropdown
import org.aristonis.mywallet.ui.components.SearchableCurrencyField
import org.aristonis.mywallet.ui.components.WalletTopAppBar
import org.aristonis.mywallet.ui.theme.MyWalletTheme

/**
 * Add-account screen. [onDone] returns to the caller (Home) — fired both on Cancel/back and once the
 * account is created (the VM's `created` flag). No nav library: the parent toggles this screen.
 */
@Composable
fun AddAccountScreen(
    onDone: () -> Unit,
    viewModel: AddAccountViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    LaunchedEffect(state.created) {
        if (state.created) {
            onDone()
            viewModel.acknowledgeCreated() // reset the retained flag so a re-open doesn't bounce back
        }
    }
    BackHandler(onBack = onDone)
    AddAccountContent(
        state = state,
        onCurrencySelected = viewModel::selectCurrency,
        onNameChanged = viewModel::setName,
        onTypeSelected = viewModel::selectAccountType,
        onOpeningBalanceChanged = viewModel::setOpeningBalance,
        onSubmit = viewModel::submit,
        onCancel = onDone,
    )
}

@Composable
private fun AddAccountContent(
    state: AddAccountUiState,
    onCurrencySelected: (String) -> Unit,
    onNameChanged: (String) -> Unit,
    onTypeSelected: (String) -> Unit,
    onOpeningBalanceChanged: (String) -> Unit,
    onSubmit: () -> Unit,
    onCancel: () -> Unit,
) {
    Scaffold(
        topBar = {
            WalletTopAppBar(title = stringResource(R.string.add_account_title), onBack = onCancel)
        },
    ) { innerPadding ->
        val selectedCurrency = state.currencies.firstOrNull { it.code == state.selectedCurrencyCode }
        val selectedType = AccountTypeRegistry.BuiltIns.all.firstOrNull { it.key == state.accountTypeKey }

        Column(
            modifier = Modifier
                .padding(innerPadding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            SearchableCurrencyField(
                label = stringResource(R.string.field_currency),
                selected = selectedCurrency,
                currencies = state.currencies,
                onSelect = onCurrencySelected,
            )

            OutlinedTextField(
                value = state.name,
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
                Text(message.text(), color = MaterialTheme.colorScheme.error)
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
                    Text(stringResource(R.string.action_save_account))
                }
            }
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun AddAccountPreview() {
    MyWalletTheme(dynamicColor = false) {
        AddAccountContent(
            state = AddAccountUiState(
                currencies = listOf(Currency("USD", "$", 2), Currency("EUR", "€", 2)),
                selectedCurrencyCode = "EUR",
                name = "Euro Savings",
            ),
            onCurrencySelected = {},
            onNameChanged = {},
            onTypeSelected = {},
            onOpeningBalanceChanged = {},
            onSubmit = {},
            onCancel = {},
        )
    }
}
