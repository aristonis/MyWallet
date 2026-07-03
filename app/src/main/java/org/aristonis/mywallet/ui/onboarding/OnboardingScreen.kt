package org.aristonis.mywallet.ui.onboarding

// UI copy is hardcoded here; string externalization (RTL/i18n, AC-25) is the SG-13 cross-cutting pass.

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.aristonis.mywallet.domain.model.AccountType
import org.aristonis.mywallet.domain.model.AccountTypeRegistry
import org.aristonis.mywallet.domain.model.Currency
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
            modifier = Modifier
                .padding(innerPadding)
                .padding(24.dp)
                .fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text("Welcome to My Wallet", style = MaterialTheme.typography.headlineMedium)
            Text(
                "Pick your base currency and add your first account.",
                style = MaterialTheme.typography.bodyMedium,
            )

            // 150+ currencies → searchable picker, not a long dropdown scroll.
            SearchableCurrencyField(
                selected = selectedCurrency,
                currencies = state.currencies,
                onSelect = onCurrencySelected,
            )

            OutlinedTextField(
                value = state.accountName,
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
                    Text("Finish setup")
                }
            }
        }
    }
}

/**
 * Base-currency field: shows the current pick and opens a searchable [SearchableListDialog] on tap.
 * Search state (the query) is ephemeral view state, so it lives in the dialog via `remember`, not
 * in the ViewModel — only the chosen code goes back up.
 */
@Composable
private fun SearchableCurrencyField(
    selected: Currency?,
    currencies: List<Currency>,
    onSelect: (String) -> Unit,
) {
    var showPicker by remember { mutableStateOf(false) }
    Column {
        Text("Base currency", style = MaterialTheme.typography.labelMedium)
        OutlinedButton(onClick = { showPicker = true }, modifier = Modifier.fillMaxWidth()) {
            Text(selected?.let { "${it.code} (${it.symbol})" } ?: "Select…", modifier = Modifier.weight(1f))
            Text("▾")
        }
    }
    if (showPicker) {
        SearchableListDialog(
            title = "Base currency",
            items = currencies,
            itemLabel = { "${it.code} — ${it.symbol}" },
            matches = { c, q -> c.code.contains(q, ignoreCase = true) || c.symbol.contains(q, ignoreCase = true) },
            onPick = {
                onSelect(it.code)
                showPicker = false
            },
            onDismiss = { showPicker = false },
        )
    }
}

/** Reusable search-and-pick dialog: a query box over a scrollable, filtered [LazyColumn]. */
@Composable
private fun <T> SearchableListDialog(
    title: String,
    items: List<T>,
    itemLabel: (T) -> String,
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
                    placeholder = { Text("Search currency") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                )
                if (filtered.isEmpty()) {
                    Text(
                        "No currency matches \"$query\"",
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

/**
 * A label + a full-width button that opens a [DropdownMenu] of [items]. Uses only stable Material 3
 * APIs (no experimental ExposedDropdownMenuBox) and no icon dependency — the caret is plain text.
 * Fine for short lists (account types); currencies use [SearchableCurrencyField] instead.
 */
@Composable
private fun <T> LabeledDropdown(
    label: String,
    selectedText: String,
    items: List<T>,
    itemLabel: (T) -> String,
    onSelect: (T) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Column {
        Text(label, style = MaterialTheme.typography.labelMedium)
        Box {
            OutlinedButton(onClick = { expanded = true }, modifier = Modifier.fillMaxWidth()) {
                Text(selectedText, modifier = Modifier.weight(1f))
                Text("▾")
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

@Composable
private fun CompletedMessage(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("You're all set", style = MaterialTheme.typography.headlineSmall)
        // Temporary end state: onboarding has no Home to hand off to yet. The routing to Home
        // (and the "is-onboarded?" gate) is wired in SG-8 — parked in backlog.md.
        Text("Your wallet is ready.", style = MaterialTheme.typography.bodyMedium)
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
