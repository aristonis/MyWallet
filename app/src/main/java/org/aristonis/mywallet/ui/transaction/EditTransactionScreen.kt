package org.aristonis.mywallet.ui.transaction

// UI copy hardcoded; localizing strings (RTL/i18n) comes later. Amount shown as a plain decimal;
// per-currency symbol + locale formatting comes later too.

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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
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
import org.aristonis.mywallet.domain.model.Transaction
import org.aristonis.mywallet.ui.components.LabeledDropdown
import org.aristonis.mywallet.ui.theme.MyWalletTheme
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import org.aristonis.mywallet.ui.label

/**
 * Edit-transaction screen. The type is fixed at load and shown read-only: income/expense may change
 * account/category/amount/date/note; a transfer's account legs are locked (shown disabled) so only its
 * amount/date/note move. A Delete button confirms then removes the row, then offers a one-tap Undo via a
 * snackbar before leaving. [onDone] returns to Home — fired on Cancel/back and once the edit is saved or
 * the delete settles (undone or let go, via the VM's one-shot flags). No nav library.
 */
@Composable
fun EditTransactionScreen(
    transactionId: Long,
    onDone: () -> Unit,
    viewModel: EditTransactionViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    // Re-hydrate whenever the target id changes; the view model is retained across the Home toggle.
    LaunchedEffect(transactionId) { viewModel.load(transactionId) }
    LaunchedEffect(state.saved) {
        if (state.saved) {
            onDone()
            viewModel.acknowledgeSaved()
        }
    }
    LaunchedEffect(state.deleted) {
        if (state.deleted) {
            onDone()
            viewModel.acknowledgeDeleted()
        }
    }
    // The row is already removed; the snackbar is the undo window. Its action restores the row, dismissal/
    // timeout finalizes — either way the VM sets `deleted`, which the effect above turns into onDone().
    LaunchedEffect(state.undoableDelete) {
        if (state.undoableDelete != null) {
            val result = snackbarHostState.showSnackbar(
                message = "Transaction deleted",
                actionLabel = "Undo",
                duration = SnackbarDuration.Short,
            )
            when (result) {
                SnackbarResult.ActionPerformed -> viewModel.undoDelete()
                SnackbarResult.Dismissed -> viewModel.confirmDelete()
            }
        }
    }
    BackHandler(onBack = onDone)
    EditTransactionContent(
        state = state,
        snackbarHostState = snackbarHostState,
        onAccountSelected = viewModel::selectAccount,
        onCategorySelected = viewModel::selectCategory,
        onSubCategorySelected = viewModel::selectSubCategory,
        onAmountChanged = viewModel::setAmount,
        onDateSelected = viewModel::setDate,
        onNoteChanged = viewModel::setNote,
        onSubmit = viewModel::submit,
        onDelete = viewModel::delete,
        onCancel = onDone,
    )
}

@Composable
private fun EditTransactionContent(
    state: EditTransactionUiState,
    snackbarHostState: SnackbarHostState,
    onAccountSelected: (Long) -> Unit,
    onCategorySelected: (Long) -> Unit,
    onSubCategorySelected: (Long?) -> Unit,
    onAmountChanged: (String) -> Unit,
    onDateSelected: (LocalDate) -> Unit,
    onNoteChanged: (String) -> Unit,
    onSubmit: () -> Unit,
    onDelete: () -> Unit,
    onCancel: () -> Unit,
) {
    Scaffold(snackbarHost = { SnackbarHost(snackbarHostState) }) { innerPadding ->
        val loaded = state.loaded
        val type = state.type
        Box(modifier = Modifier.padding(innerPadding).fillMaxSize()) {
            if (loaded == null || type == null) {
                CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
            } else {
                EditTransactionForm(
                    state = state,
                    type = type,
                    date = state.date ?: loaded.date,
                    onAccountSelected = onAccountSelected,
                    onCategorySelected = onCategorySelected,
            onSubCategorySelected = onSubCategorySelected,
                    onAmountChanged = onAmountChanged,
                    onDateSelected = onDateSelected,
                    onNoteChanged = onNoteChanged,
                    onSubmit = onSubmit,
                    onDelete = onDelete,
                    onCancel = onCancel,
                )
            }
        }
    }
}

@Composable
private fun EditTransactionForm(
    state: EditTransactionUiState,
    type: TransactionType,
    date: LocalDate,
    onAccountSelected: (Long) -> Unit,
    onCategorySelected: (Long) -> Unit,
    onSubCategorySelected: (Long?) -> Unit,
    onAmountChanged: (String) -> Unit,
    onDateSelected: (LocalDate) -> Unit,
    onNoteChanged: (String) -> Unit,
    onSubmit: () -> Unit,
    onDelete: () -> Unit,
    onCancel: () -> Unit,
) {
    var confirmingDelete by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .padding(24.dp)
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Edit transaction", style = MaterialTheme.typography.headlineMedium, modifier = Modifier.weight(1f))
            TextButton(onClick = onCancel) { Text("Cancel") }
        }

        // The kind is a fact of the row, not a choice — show it, never let it be re-picked.
        Text(typeLabel(type), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)

        AccountAndCategoryFields(
            state = state,
            type = type,
            onAccountSelected = onAccountSelected,
            onCategorySelected = onCategorySelected,
            onSubCategorySelected = onSubCategorySelected,
        )

        val selectedAccount = state.accounts.firstOrNull { it.id == state.selectedAccountId }
        OutlinedTextField(
            value = state.amountInput,
            onValueChange = onAmountChanged,
            label = { Text(amountLabel(selectedAccount)) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
            modifier = Modifier.fillMaxWidth(),
        )

        DateField(date = date, onDateSelected = onDateSelected)

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

        TextButton(
            onClick = { confirmingDelete = true },
            enabled = !state.isSubmitting && state.undoableDelete == null,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("Delete", color = MaterialTheme.colorScheme.error)
        }
    }

    if (confirmingDelete) {
        AlertDialog(
            onDismissRequest = { confirmingDelete = false },
            title = { Text("Delete this transaction?") },
            text = { Text("This removes the transaction. You can undo right after.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmingDelete = false
                    onDelete()
                }) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { confirmingDelete = false }) { Text("Cancel") } },
        )
    }
}

/**
 * Income/expense expose an editable account + category picker; a transfer shows both account legs as
 * disabled read-only fields, because its stored rate is bound to that currency pair and can't move here.
 */
@Composable
private fun AccountAndCategoryFields(
    state: EditTransactionUiState,
    type: TransactionType,
    onAccountSelected: (Long) -> Unit,
    onCategorySelected: (Long) -> Unit,
    onSubCategorySelected: (Long?) -> Unit,
) {
    if (type == TransactionType.TRANSFER) {
        LockedField(label = "From account", value = accountLabel(state.selectedAccountId, state.accounts))
        LockedField(label = "To account", value = accountLabel(state.destAccountId, state.accounts))
        return
    }

    val selectedAccount = state.accounts.firstOrNull { it.id == state.selectedAccountId }
    LabeledDropdown(
        label = "Account",
        selectedText = selectedAccount?.let { "${it.name} (${it.currencyCode})" } ?: "Select…",
        items = state.accounts,
        itemLabel = { "${it.name} (${it.currencyCode})" },
        onSelect = { onAccountSelected(it.id) },
    )
    val category = state.categoriesForType.firstOrNull { it.id == state.selectedCategoryId }
    LabeledDropdown(
        label = "Category",
        selectedText = category?.label() ?: "Select…",
        items = state.categoriesForType,
        itemLabel = { it.label() },
        onSelect = { onCategorySelected(it.id) },
    )
    // Offered only when the chosen category has children, matching the add form. Without it the
    // stored sub-category would be unreachable — and re-picking the same category clears it, so
    // there would be no way to put it back.
    val subCategories = state.subCategoriesForSelected
    if (subCategories.isNotEmpty()) {
        val chosen = subCategories.firstOrNull { it.id == state.selectedSubCategoryId }
        LabeledDropdown(
            label = "Sub-category (optional)",
            selectedText = chosen?.label() ?: "None",
            items = subCategories,
            itemLabel = { it.label() },
            onSelect = { onSubCategorySelected(it.id) },
        )
    }
}

/** A labelled, disabled field — looks like a picker but can't be changed (a transfer's locked legs). */
@Composable
private fun LockedField(label: String, value: String) {
    Column {
        Text(label, style = MaterialTheme.typography.labelMedium)
        OutlinedButton(onClick = {}, enabled = false, modifier = Modifier.fillMaxWidth()) {
            Text(value, modifier = Modifier.weight(1f))
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

// An account archived after the transaction was recorded won't be in the (archived-filtered) list; a
// dash keeps the locked field readable instead of crashing (mirrors the transactions-list defensive read).
private fun accountLabel(id: Long?, accounts: List<Account>): String =
    accounts.firstOrNull { it.id == id }?.let { "${it.name} (${it.currencyCode})" } ?: "—"

@OptIn(ExperimentalMaterial3Api::class)
@Preview(showBackground = true)
@Composable
private fun EditTransactionPreview() {
    MyWalletTheme(dynamicColor = false) {
        EditTransactionContent(
            state = EditTransactionUiState(
                loaded = Transaction.Expense(
                    id = 1, accountId = 1, amount = Money.of("12.50", "USD"), categoryId = 2,
                    date = LocalDate.of(2026, 7, 3), note = "Lunch",
                ),
                type = TransactionType.EXPENSE,
                accounts = listOf(Account(id = 1, name = "Cash", typeKey = "cash", currencyCode = "USD", openingBalance = Money.zero("USD"))),
                allCategories = listOf(Category(id = 2, name = "Food", kind = org.aristonis.mywallet.domain.model.CategoryKind.EXPENSE)),
                selectedAccountId = 1,
                selectedCategoryId = 2,
                amountInput = "12.50",
                date = LocalDate.of(2026, 7, 3),
                note = "Lunch",
            ),
            snackbarHostState = remember { SnackbarHostState() },
            onAccountSelected = {},
            onCategorySelected = {},
            onSubCategorySelected = {},
            onAmountChanged = {},
            onDateSelected = {},
            onNoteChanged = {},
            onSubmit = {},
            onDelete = {},
            onCancel = {},
        )
    }
}
