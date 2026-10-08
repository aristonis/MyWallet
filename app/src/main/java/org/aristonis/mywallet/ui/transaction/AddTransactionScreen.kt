package org.aristonis.mywallet.ui.transaction

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
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
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.aristonis.mywallet.R
import org.aristonis.mywallet.domain.model.Account
import org.aristonis.mywallet.domain.model.Category
import org.aristonis.mywallet.domain.model.Money
import org.aristonis.mywallet.ui.components.LabeledDropdown
import org.aristonis.mywallet.ui.components.WalletTopAppBar
import org.aristonis.mywallet.ui.format.bidiIsolate
import org.aristonis.mywallet.ui.format.rememberDateFormatter
import org.aristonis.mywallet.ui.icons.WalletIcons
import org.aristonis.mywallet.ui.theme.MyWalletTheme
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import org.aristonis.mywallet.ui.label
import org.aristonis.mywallet.ui.text
import org.aristonis.mywallet.ui.message.text

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
        onSubCategorySelected = viewModel::selectSubCategory,
        onDestAccountSelected = viewModel::selectDestAccount,
        onAmountChanged = viewModel::setAmount,
        onRateChanged = viewModel::setRate,
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
    onSubCategorySelected: (Long?) -> Unit,
    onDestAccountSelected: (Long) -> Unit,
    onAmountChanged: (String) -> Unit,
    onRateChanged: (currencyCode: String, input: String) -> Unit,
    onDateSelected: (LocalDate) -> Unit,
    onNoteChanged: (String) -> Unit,
    onSubmit: () -> Unit,
    onCancel: () -> Unit,
) {
    Scaffold(
        topBar = {
            WalletTopAppBar(title = stringResource(R.string.add_transaction_title), onBack = onCancel)
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .padding(innerPadding)
                .padding(16.dp)
                .fillMaxSize()
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            TransactionTypeSelector(selected = state.type, onSelect = onTypeSelected)

            val selectedAccount = state.accounts.firstOrNull { it.id == state.selectedAccountId }
            AmountField(
                value = state.amountInput,
                label = amountLabel(selectedAccount),
                onValueChange = onAmountChanged,
                supportingText = state.baseEquivalent?.let {
                    stringResource(R.string.add_transaction_base_equivalent, bidiIsolate(it))
                },
            )
            LabeledDropdown(
                label = stringResource(
                    if (state.type == TransactionType.TRANSFER) R.string.field_from_account else R.string.field_account,
                ),
                selectedText = selectedAccount?.let { accountLabel(it) }
                    ?: stringResource(R.string.value_select_prompt),
                items = state.accounts,
                itemLabel = { accountLabel(it) },
                onSelect = { onAccountSelected(it.id) },
            )

            when (state.type) {
                TransactionType.TRANSFER -> {
                    val dest = state.accounts.firstOrNull { it.id == state.destAccountId }
                    LabeledDropdown(
                        label = stringResource(R.string.field_to_account),
                        selectedText = dest?.let { accountLabel(it) }
                            ?: stringResource(R.string.value_select_prompt),
                        items = state.destAccounts,
                        itemLabel = { accountLabel(it) },
                        onSelect = { onDestAccountSelected(it.id) },
                    )
                }
                else -> {
                    val category = state.categoriesForType.firstOrNull { it.id == state.selectedCategoryId }
                    LabeledDropdown(
                        label = stringResource(R.string.field_category),
                        selectedText = category?.label()?.text() ?: stringResource(R.string.value_select_prompt),
                        items = state.categoriesForType,
                        itemLabel = { it.label().text() },
                        onSelect = { onCategorySelected(it.id) },
                    )
                    // Only offered when the chosen category actually has children, so an ordinary
                    // entry stays a two-tap job rather than growing a field that is always empty.
                    val subCategories = state.subCategoriesForSelected
                    if (subCategories.isNotEmpty()) {
                        val chosen = subCategories.firstOrNull { it.id == state.selectedSubCategoryId }
                        LabeledDropdown(
                            label = stringResource(R.string.field_sub_category),
                            selectedText = chosen?.label()?.text() ?: stringResource(R.string.value_none),
                            items = subCategories,
                            itemLabel = { it.label().text() },
                            onSelect = { onSubCategorySelected(it.id) },
                        )
                    }
                }
            }

            RateSection(state = state, onRateChanged = onRateChanged)

            DateField(date = state.date, onDateSelected = onDateSelected)

            OutlinedTextField(
                value = state.note,
                onValueChange = onNoteChanged,
                label = { Text(stringResource(R.string.field_note)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )

            state.error?.let { message ->
                Text(
                    text = message.text(),
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodyMedium,
                )
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
                    Text(stringResource(R.string.action_save_transaction))
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
        Text(stringResource(R.string.field_date), style = MaterialTheme.typography.labelMedium)
        // Written the way the reader's locale writes dates; the ISO form is for storage, not for people.
        val formatDate = rememberDateFormatter()
        OutlinedButton(onClick = { showPicker = true }, modifier = Modifier.fillMaxWidth()) {
            Text(formatDate(date), modifier = Modifier.weight(1f))
            Icon(imageVector = WalletIcons.Date, contentDescription = null)
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
                }) { Text(stringResource(R.string.action_ok)) }
            },
            dismissButton = {
                TextButton(onClick = { showPicker = false }) { Text(stringResource(R.string.action_cancel)) }
            },
        ) {
            DatePicker(state = pickerState)
        }
    }
}

@Composable
internal fun typeLabel(type: TransactionType): String = stringResource(
    when (type) {
        TransactionType.INCOME -> R.string.transaction_type_income
        TransactionType.EXPENSE -> R.string.transaction_type_expense
        TransactionType.TRANSFER -> R.string.transaction_type_transfer
    },
)

/** The account currency belongs in the amount label: it is what the typed number will be recorded in. */
@Composable
internal fun amountLabel(account: Account?): String = account
    ?.let { stringResource(R.string.field_amount_in_currency, it.currencyCode) }
    ?: stringResource(R.string.field_amount)

@Composable
internal fun accountLabel(account: Account): String =
    stringResource(R.string.account_with_currency, account.name, account.currencyCode)

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
            onSubCategorySelected = {},
            onAmountChanged = {},
            onRateChanged = { _, _ -> },
            onDateSelected = {},
            onNoteChanged = {},
            onSubmit = {},
            onCancel = {},
        )
    }
}

/**
 * Income, expense or transfer, as one connected control. Exactly one always applies, and tabs would
 * suggest three views of the same thing rather than three kinds of entry.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun TransactionTypeSelector(
    selected: TransactionType,
    onSelect: (TransactionType) -> Unit,
) {
    SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
        TransactionType.entries.forEachIndexed { index, type ->
            SegmentedButton(
                selected = selected == type,
                onClick = { onSelect(type) },
                shape = SegmentedButtonDefaults.itemShape(index = index, count = TransactionType.entries.size),
            ) {
                Text(typeLabel(type), maxLines = 1)
            }
        }
    }
}

/**
 * The amount, given the size it deserves: it is the one field every entry has, and the one a user
 * checks against a receipt. The decimal keyboard is what makes it a two-second job on a phone.
 */
@Composable
internal fun AmountField(
    value: String,
    label: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    supportingText: String? = null,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        supportingText = supportingText?.let { hint -> { Text(hint) } },
        singleLine = true,
        textStyle = MaterialTheme.typography.headlineSmall,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
        modifier = modifier.fillMaxWidth(),
    )
}
