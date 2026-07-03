package org.aristonis.mywallet.ui.transaction

// UI copy hardcoded; localizing strings (RTL/i18n) comes later. Amount shown as a plain decimal;
// per-currency symbol + locale formatting comes later too.

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.aristonis.mywallet.domain.model.Account
import org.aristonis.mywallet.domain.model.Category
import org.aristonis.mywallet.domain.model.Money
import org.aristonis.mywallet.ui.components.LabeledDropdown
import org.aristonis.mywallet.ui.theme.MyWalletTheme
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

/**
 * Add-transaction screen. A type selector (Income / Expense / Transfer) drives which fields show:
 * income/expense pick a category, transfer picks a destination account. [onDone] returns to Home —
 * fired on Cancel/back and once the transaction is recorded (the VM's `saved` flag). No nav library.
 */
@Composable
fun AddTransactionScreen(
    onDone: () -> Unit,
    viewModel: AddTransactionViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    LaunchedEffect(state.saved) {
        if (state.saved) {
            onDone()
            viewModel.acknowledgeSaved() // reset the retained flag so a re-open doesn't bounce back
        }
    }
    BackHandler(onBack = onDone)
    AddTransactionContent(
        state = state,
        onTypeSelected = viewModel::selectType,
        onAccountSelected = viewModel::selectAccount,
        onCategorySelected = viewModel::selectCategory,
        onDestAccountSelected = viewModel::selectDestAccount,
        onAmountChanged = viewModel::setAmount,
        onDateSelected = viewModel::setDate,
        onNoteChanged = viewModel::setNote,
        onSubmit = viewModel::submit,
        onCancel = onDone,
    )
}

@Composable
private fun AddTransactionContent(
    state: AddTransactionUiState,
    onTypeSelected: (TransactionType) -> Unit,
    onAccountSelected: (Long) -> Unit,
    onCategorySelected: (Long) -> Unit,
    onDestAccountSelected: (Long) -> Unit,
    onAmountChanged: (String) -> Unit,
    onDateSelected: (LocalDate) -> Unit,
    onNoteChanged: (String) -> Unit,
    onSubmit: () -> Unit,
    onCancel: () -> Unit,
) {
    Scaffold { innerPadding ->
        Column(
            modifier = Modifier
                .padding(innerPadding)
                .padding(24.dp)
                .fillMaxSize()
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Add transaction", style = MaterialTheme.typography.headlineMedium, modifier = Modifier.weight(1f))
                TextButton(onClick = onCancel) { Text("Cancel") }
            }

            TabRow(selectedTabIndex = state.type.ordinal) {
                TransactionType.entries.forEach { type ->
                    Tab(
                        selected = state.type == type,
                        onClick = { onTypeSelected(type) },
                        text = { Text(typeLabel(type)) },
                    )
                }
            }

            val selectedAccount = state.accounts.firstOrNull { it.id == state.selectedAccountId }
            LabeledDropdown(
                label = if (state.type == TransactionType.TRANSFER) "From account" else "Account",
                selectedText = selectedAccount?.let { "${it.name} (${it.currencyCode})" } ?: "Select…",
                items = state.accounts,
                itemLabel = { "${it.name} (${it.currencyCode})" },
                onSelect = { onAccountSelected(it.id) },
            )

            when (state.type) {
                TransactionType.TRANSFER -> {
                    val dest = state.accounts.firstOrNull { it.id == state.destAccountId }
                    LabeledDropdown(
                        label = "To account",
                        selectedText = dest?.let { "${it.name} (${it.currencyCode})" } ?: "Select…",
                        items = state.destAccounts,
                        itemLabel = { "${it.name} (${it.currencyCode})" },
                        onSelect = { onDestAccountSelected(it.id) },
                    )
                }
                else -> {
                    val category = state.categoriesForType.firstOrNull { it.id == state.selectedCategoryId }
                    LabeledDropdown(
                        label = "Category",
                        selectedText = category?.name ?: "Select…",
                        items = state.categoriesForType,
                        itemLabel = Category::name,
                        onSelect = { onCategorySelected(it.id) },
                    )
                }
            }

            OutlinedTextField(
                value = state.amountInput,
                onValueChange = onAmountChanged,
                label = { Text(amountLabel(selectedAccount)) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                modifier = Modifier.fillMaxWidth(),
            )

            DateField(date = state.date, onDateSelected = onDateSelected)

            OutlinedTextField(
                value = state.note,
                onValueChange = onNoteChanged,
                label = { Text("Note (optional)") },
                singleLine = true,
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
}

/** A labelled button showing the chosen date; tapping it opens the Material date picker. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DateField(date: LocalDate, onDateSelected: (LocalDate) -> Unit) {
    var showPicker by remember { mutableStateOf(false) }
    Column {
        Text("Date", style = MaterialTheme.typography.labelMedium)
        OutlinedButton(onClick = { showPicker = true }, modifier = Modifier.fillMaxWidth()) {
            Text(date.toString(), modifier = Modifier.weight(1f))
            Text("▾")
        }
    }
    if (showPicker) {
        // The picker works in UTC-midnight millis; convert in UTC both ways so the calendar day never
        // shifts by one (using the system zone would drift in negative offsets).
        val pickerState = rememberDatePickerState(
            initialSelectedDateMillis = date.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli(),
        )
        DatePickerDialog(
            onDismissRequest = { showPicker = false },
            confirmButton = {
                TextButton(onClick = {
                    pickerState.selectedDateMillis?.let { millis ->
                        onDateSelected(Instant.ofEpochMilli(millis).atZone(ZoneOffset.UTC).toLocalDate())
                    }
                    showPicker = false
                }) { Text("OK") }
            },
            dismissButton = {
                TextButton(onClick = { showPicker = false }) { Text("Cancel") }
            },
        ) {
            DatePicker(state = pickerState)
        }
    }
}

private fun typeLabel(type: TransactionType): String = when (type) {
    TransactionType.INCOME -> "Income"
    TransactionType.EXPENSE -> "Expense"
    TransactionType.TRANSFER -> "Transfer"
}

private fun amountLabel(account: Account?): String =
    account?.let { "Amount (${it.currencyCode})" } ?: "Amount"

@OptIn(ExperimentalMaterial3Api::class)
@Preview(showBackground = true)
@Composable
private fun AddTransactionPreview() {
    MyWalletTheme(dynamicColor = false) {
        AddTransactionContent(
            state = AddTransactionUiState(
                type = TransactionType.EXPENSE,
                accounts = listOf(Account(id = 1, name = "Cash", typeKey = "cash", currencyCode = "USD", openingBalance = Money.zero("USD"))),
                allCategories = listOf(Category(id = 2, name = "Food", kind = org.aristonis.mywallet.domain.model.CategoryKind.EXPENSE)),
                selectedAccountId = 1,
                amountInput = "12.50",
                date = LocalDate.of(2026, 7, 3),
            ),
            onTypeSelected = {},
            onAccountSelected = {},
            onCategorySelected = {},
            onDestAccountSelected = {},
            onAmountChanged = {},
            onDateSelected = {},
            onNoteChanged = {},
            onSubmit = {},
            onCancel = {},
        )
    }
}
