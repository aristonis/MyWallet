package org.aristonis.mywallet.ui.account

// UI copy hardcoded; localizing strings (RTL/i18n) comes later.

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.aristonis.mywallet.domain.model.Account
import org.aristonis.mywallet.domain.model.AccountType
import org.aristonis.mywallet.domain.model.AccountTypeRegistry
import org.aristonis.mywallet.domain.model.Currency
import org.aristonis.mywallet.domain.model.Money
import org.aristonis.mywallet.ui.components.LabeledDropdown
import org.aristonis.mywallet.ui.components.SearchableCurrencyField
import org.aristonis.mywallet.ui.theme.MyWalletTheme

private const val CURRENCY_LOCKED_HINT = "Currency is locked once an account has transactions"

/**
 * Edit-account screen. [onDone] returns to the caller (Manage Accounts) — fired on Cancel/back and once
 * the edit is saved (the VM's `saved` flag). The currency picker is disabled once the account has
 * transactions, since the domain refuses a currency change there. No nav library: the parent toggles this.
 */
@Composable
fun EditAccountScreen(
    accountId: Long,
    onDone: () -> Unit,
    viewModel: EditAccountViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    // Re-hydrate whenever the target id changes; the view model is retained across the Home toggle.
    LaunchedEffect(accountId) { viewModel.load(accountId) }
    LaunchedEffect(state.saved) {
        if (state.saved) {
            onDone()
            viewModel.acknowledgeSaved() // reset the retained flag so a re-open doesn't bounce back
        }
    }
    BackHandler(onBack = onDone)
    EditAccountContent(
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
private fun EditAccountContent(
    state: EditAccountUiState,
    onCurrencySelected: (String) -> Unit,
    onNameChanged: (String) -> Unit,
    onTypeSelected: (String) -> Unit,
    onOpeningBalanceChanged: (String) -> Unit,
    onSubmit: () -> Unit,
    onCancel: () -> Unit,
) {
    Scaffold { innerPadding ->
        val error = state.error
        Box(modifier = Modifier.padding(innerPadding).fillMaxSize()) {
            when {
                // A missing row is a fail-loud dead end, not a blank editor — show why and offer Back.
                state.loaded == null && error != null -> MissingAccount(message = error, onCancel = onCancel)
                state.loaded == null -> CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
                else -> EditAccountForm(
                    state = state,
                    onCurrencySelected = onCurrencySelected,
                    onNameChanged = onNameChanged,
                    onTypeSelected = onTypeSelected,
                    onOpeningBalanceChanged = onOpeningBalanceChanged,
                    onSubmit = onSubmit,
                    onCancel = onCancel,
                )
            }
        }
    }
}

@Composable
private fun EditAccountForm(
    state: EditAccountUiState,
    onCurrencySelected: (String) -> Unit,
    onNameChanged: (String) -> Unit,
    onTypeSelected: (String) -> Unit,
    onOpeningBalanceChanged: (String) -> Unit,
    onSubmit: () -> Unit,
    onCancel: () -> Unit,
) {
    val selectedCurrency = state.currencies.firstOrNull { it.code == state.selectedCurrencyCode }
    val selectedType = AccountTypeRegistry.BuiltIns.all.firstOrNull { it.key == state.accountTypeKey }

    Column(
        modifier = Modifier.padding(24.dp).fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Edit account", style = MaterialTheme.typography.headlineMedium, modifier = Modifier.weight(1f))
            TextButton(onClick = onCancel) { Text("Cancel") }
        }

        if (state.currencyLocked) {
            LockedCurrencyField(currencyCode = state.selectedCurrencyCode ?: "—")
        } else {
            SearchableCurrencyField(
                label = "Currency",
                selected = selectedCurrency,
                currencies = state.currencies,
                onSelect = onCurrencySelected,
            )
        }

        OutlinedTextField(
            value = state.name,
            onValueChange = onNameChanged,
            label = { Text("Account name") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )

        LabeledDropdown(
            label = "Account type",
            selectedText = selectedType?.displayName ?: "Select…",
            items = AccountTypeRegistry.BuiltIns.all,
            itemLabel = AccountType::displayName,
            onSelect = { onTypeSelected(it.key) },
        )

        OutlinedTextField(
            value = state.openingBalanceInput,
            onValueChange = onOpeningBalanceChanged,
            label = { Text("Opening balance (optional)") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
            modifier = Modifier.fillMaxWidth(),
        )

        state.error?.let { message ->
            Text(message, color = MaterialTheme.colorScheme.error)
        }

        Spacer(Modifier.weight(1f))

        Button(
            onClick = onSubmit,
            enabled = state.canSubmit,
            modifier = Modifier.fillMaxWidth(),
        ) {
            if (state.isSubmitting) {
                CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
            } else {
                Text("Save")
            }
        }
    }
}

/** A disabled currency field with a short hint, shown once the account has transactions (currency fixed). */
@Composable
private fun LockedCurrencyField(currencyCode: String) {
    Column {
        Text("Currency", style = MaterialTheme.typography.labelMedium)
        OutlinedButton(onClick = {}, enabled = false, modifier = Modifier.fillMaxWidth()) {
            Text(currencyCode, modifier = Modifier.weight(1f))
        }
        Text(
            CURRENCY_LOCKED_HINT,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun MissingAccount(message: String, onCancel: () -> Unit) {
    Column(
        modifier = Modifier.padding(24.dp).fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(message, color = MaterialTheme.colorScheme.error)
        TextButton(onClick = onCancel) { Text("Back") }
    }
}

@Preview(showBackground = true)
@Composable
private fun EditAccountPreview() {
    MyWalletTheme(dynamicColor = false) {
        EditAccountContent(
            state = EditAccountUiState(
                loaded = Account(id = 1, name = "Euro Savings", typeKey = "savings", currencyCode = "EUR", openingBalance = Money.of("250", "EUR")),
                currencies = listOf(Currency("USD", "$", 2), Currency("EUR", "€", 2)),
                selectedCurrencyCode = "EUR",
                name = "Euro Savings",
                accountTypeKey = "savings",
                openingBalanceInput = "250",
                currencyLocked = true,
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
