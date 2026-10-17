package org.aristonis.mywallet.ui.transaction

import android.content.res.Configuration
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.TextField
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.error
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.tooling.preview.PreviewParameter
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.aristonis.mywallet.R
import org.aristonis.mywallet.domain.model.Account
import org.aristonis.mywallet.domain.model.DebtSide
import org.aristonis.mywallet.domain.model.FeeMode
import org.aristonis.mywallet.ui.components.WalletTopAppBar
import org.aristonis.mywallet.ui.format.bidiIsolate
import org.aristonis.mywallet.ui.format.rememberDateFormatter
import org.aristonis.mywallet.ui.icons.WalletIcons
import org.aristonis.mywallet.ui.label
import org.aristonis.mywallet.ui.message.text
import org.aristonis.mywallet.ui.message.UiMessage
import org.aristonis.mywallet.ui.text
import org.aristonis.mywallet.ui.theme.MyWalletTheme
import org.aristonis.mywallet.ui.theme.WalletTheme
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

/**
 * Add-transaction screen. A type selector (Income / Expense / Transfer) drives which fields show:
 * income/expense pick a category, transfer picks a destination account. [onDone] leaves without
 * saving (Cancel/back). [onSaved] leaves once the transaction is recorded (the VM's `savedOn`) and
 * carries the date it was stored under, so a list showing only some dates can say when the new entry
 * falls outside them.
 */
@Composable
fun AddTransactionScreen(
    onDone: () -> Unit,
    onSaved: (LocalDate) -> Unit,
    viewModel: AddTransactionViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    LaunchedEffect(state.savedOn) {
        // The date the save stored, not the form's: acknowledging resets the form for the next entry.
        state.savedOn?.let { savedOn ->
            onSaved(savedOn)
            viewModel.acknowledgeSaved() // reset the retained flag so a re-open doesn't bounce back
        }
    }
    BackHandler(onBack = onDone)
    AddTransactionContent(
        state = state,
        onTypeSelected = viewModel::selectType,
        onCounterpartyChanged = viewModel::setCounterparty,
        onSuggestionSelected = viewModel::selectCounterpartySuggestion,
        onCurrencySelected = viewModel::selectCurrency,
        onDebtSideSelected = viewModel::selectDebtSide,
        onAccountSelected = viewModel::selectAccount,
        onCategorySelected = viewModel::selectCategory,
        onSubCategorySelected = viewModel::selectSubCategory,
        onDestAccountSelected = viewModel::selectDestAccount,
        onAmountChanged = viewModel::setAmount,
        onRateChanged = viewModel::setRate,
        onDateSelected = viewModel::setDate,
        onNoteChanged = viewModel::setNote,
        onDeductionEdited = viewModel::editDeduction,
        onRetryDeductions = viewModel::retryDeductions,
        onPaidLaterEnabled = viewModel::setPaidLaterEnabled,
        onPaidNowChanged = viewModel::setPaidNow,
        onPaidLaterNameChanged = viewModel::setPaidLaterCounterparty,
        onPaidLaterDebtSelected = viewModel::selectPaidLaterDebt,
        onPaidLaterDateSelected = viewModel::setPaidLaterDueDate,
        onFeeValueChanged = viewModel::setFeeValue,
        onFeeModeChanged = viewModel::selectFeeMode,
        onFeeCategorySelected = viewModel::selectFeeCategory,
        onRetryFeeCategory = viewModel::retryFeeCategory,
        onSubmit = viewModel::submit,
        onCancel = onDone,
    )
}

@Composable
internal fun AddTransactionContent(
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
    onFeeValueChanged: (String) -> Unit,
    onFeeModeChanged: (FeeMode) -> Unit,
    onFeeCategorySelected: (Long?) -> Unit,
    onRetryFeeCategory: () -> Unit,
    onCounterpartyChanged: (String) -> Unit = {},
    onSuggestionSelected: (String) -> Unit = {},
    onCurrencySelected: (String) -> Unit = {},
    onDebtSideSelected: (DebtSide) -> Unit = {},
    onDeductionEdited: (Long, DeductionEdit) -> Unit = { _, _ -> },
    onRetryDeductions: () -> Unit = {},
    onPaidLaterEnabled: (Boolean) -> Unit = {},
    onPaidNowChanged: (String) -> Unit = {},
    onPaidLaterNameChanged: (String) -> Unit = {},
    onPaidLaterDebtSelected: (Long) -> Unit = {},
    onPaidLaterDateSelected: (LocalDate?) -> Unit = {},
) {
    var showingMore by remember { mutableStateOf(false) }
    Scaffold(
        topBar = {
            WalletTopAppBar(title = stringResource(R.string.add_transaction_title), onBack = onCancel)
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .padding(innerPadding)
                .padding(WalletTheme.dimensions.screenPadding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(WalletTheme.dimensions.regionGap),
        ) {
            KindChoices(
                selected = state.type,
                onSelect = onTypeSelected,
                onMore = { showingMore = true },
            )
            val rule = EntryFormRules.forType(state.type)
            val selectedAccount = state.accounts.firstOrNull { it.id == state.selectedAccountId }
            AmountField(
                value = state.amountInput,
                label = formAmountLabel(rule.currency(state.formInputs(), state.accounts)),
                onValueChange = onAmountChanged,
                supportingText = state.baseEquivalent?.let {
                    stringResource(R.string.add_transaction_base_equivalent, bidiIsolate(it))
                },
                errorText = state.error?.takeIf { it.isAmountEntryError(state.errorIsAmountInput) }?.text(),
            )

            TransactionFieldsCard {
                if (state.type !in standardTransactionTypes) {
                    Text(typeLabel(state.type), style = MaterialTheme.typography.titleMedium)
                }
                if (rule.requiresCounterparty || rule.requiresCurrency || rule.showsDebtSide) {
                    TransactionFieldSurface {
                        DebtKindFields(
                            state.type, state.formInputs(), state.fx?.currencies.orEmpty(),
                            state.counterpartySuggestions, onCounterpartyChanged, onSuggestionSelected,
                            onCurrencySelected, onDebtSideSelected,
                        )
                    }
                }

                if (rule.requiresAccount) {
                    TransactionChoiceField(
                        label = stringResource(
                            if (state.type == TransactionType.TRANSFER) R.string.field_from_account
                            else R.string.field_account,
                        ),
                        selectedText = selectedAccount?.let { accountLabel(it) }
                            ?: stringResource(R.string.value_select_prompt),
                        items = state.accounts,
                        itemLabel = { accountLabel(it) },
                        onSelect = { onAccountSelected(it.id) },
                    )
                    EntryFieldError(state.error.takeIf { it == UiMessage.AccountRequired })
                }

                when (state.type) {
                    TransactionType.TRANSFER -> {
                        if (state.accounts.size <= 1 || state.destAccounts.isEmpty()) {
                            Text(
                                text = stringResource(
                                    if (state.accounts.isEmpty()) R.string.transfer_add_first_account
                                    else R.string.transfer_add_second_account,
                                ),
                                style = MaterialTheme.typography.bodyMedium,
                            )
                        } else {
                            val dest = state.accounts.firstOrNull { it.id == state.destAccountId }
                            TransactionChoiceField(
                                label = stringResource(R.string.field_to_account),
                                selectedText = dest?.let { accountLabel(it) }
                                    ?: stringResource(R.string.value_select_prompt),
                                items = state.destAccounts,
                                itemLabel = { accountLabel(it) },
                                onSelect = { onDestAccountSelected(it.id) },
                            )
                            EntryFieldError(state.error.takeIf { it == UiMessage.DestinationAccountRequired })
                        }
                    }
                    else -> if (rule.requiresCategory) {
                        val category = state.categoriesForType.firstOrNull { it.id == state.selectedCategoryId }
                        TransactionChoiceField(
                            label = stringResource(R.string.field_category),
                            selectedText = category?.label()?.text() ?: stringResource(R.string.value_select_prompt),
                            items = state.categoriesForType,
                            itemLabel = { it.label().text() },
                            onSelect = { onCategorySelected(it.id) },
                        )
                        EntryFieldError(state.error.takeIf { it == UiMessage.CategoryRequired })
                        // Only offered when the chosen category actually has children, so an ordinary
                        // entry stays a two-tap job rather than growing a field that is always empty.
                        val subCategories = state.subCategoriesForSelected
                        if (subCategories.isNotEmpty()) {
                            val chosen = subCategories.firstOrNull { it.id == state.selectedSubCategoryId }
                            TransactionChoiceField(
                                label = stringResource(R.string.field_sub_category),
                                selectedText = chosen?.label()?.text() ?: stringResource(R.string.value_none),
                                items = subCategories,
                                itemLabel = { it.label().text() },
                                onSelect = { onSubCategorySelected(it.id) },
                            )
                        }
                    }
                }

                if (state.type == TransactionType.INCOME) {
                    TransactionFieldSurface {
                        DeductionsSection(
                            state.deductions, state.fx?.currencies.orEmpty(), selectedAccount?.currencyCode,
                            onDeductionEdited, onRetryDeductions,
                        )
                    }
                }

                if (state.type == TransactionType.EXPENSE) {
                    TransactionFieldSurface {
                        PaidLaterFields(state.paidLater, state.paidLaterDebts, selectedAccount?.currencyCode,
                            state.fx?.currencies.orEmpty(), onPaidLaterEnabled, onPaidNowChanged,
                            onPaidLaterNameChanged, onPaidLaterDebtSelected, onPaidLaterDateSelected)
                        state.paidLaterDebtError?.takeIf { state.paidLater.enabled }?.let {
                            Text(it.text(), color = MaterialTheme.colorScheme.error)
                        }
                    }
                }
                if (state.type != TransactionType.EXPENSE || state.paidLater.feeEnabled) {
                    FeeField(state.fee, state.type, state.accounts.firstOrNull { it.id == state.destAccountId }?.name,
                        selectedAccount?.currencyCode, state.fx?.currencies.orEmpty(), state.allCategories,
                        onFeeValueChanged, onFeeModeChanged, onFeeCategorySelected, onRetryFeeCategory)
                }
                RateSection(state = state, onRateChanged = onRateChanged)
                FeeQuoteError(state.fee, rateAlreadyExplained = state.rateRequired)

                DateField(date = state.date, onDateSelected = onDateSelected)

                TextField(
                    value = state.note,
                    onValueChange = onNoteChanged,
                    label = { Text(stringResource(R.string.field_note)) },
                    singleLine = true,
                    shape = MaterialTheme.shapes.medium,
                    colors = transactionFieldColors(),
                    modifier = Modifier.fillMaxWidth(),
                )

                state.error?.takeUnless { it.isInlineEntryError(state.errorIsAmountInput) }?.let { message ->
                    Text(
                        text = message.text(),
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }

            Spacer(Modifier.weight(1f))

            Button(
                onClick = onSubmit,
                enabled = state.canSubmit,
                modifier = Modifier.fillMaxWidth().heightIn(min = WalletTheme.dimensions.minTouchTarget),
                shape = MaterialTheme.shapes.medium,
            ) {
                if (state.isSubmitting) {
                    CircularProgressIndicator(modifier = Modifier.size(WalletTheme.dimensions.iconSize), strokeWidth = 2.dp)
                } else {
                    Text(stringResource(R.string.action_save_transaction))
                }
            }
        }
    }
    if (showingMore) MoreKindsSheet(onTypeSelected, onDismiss = { showingMore = false })
}

/** A labelled button showing the chosen date; tapping it opens the Material date picker. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun DateField(date: LocalDate, onDateSelected: (LocalDate) -> Unit) {
    var showPicker by remember { mutableStateOf(false) }
    Column {
        // Written the way the reader's locale writes dates; the ISO form is for storage, not for people.
        val formatDate = rememberDateFormatter()
        TransactionFieldButton(
            label = stringResource(R.string.field_date),
            value = formatDate(date),
            onClick = { showPicker = true },
            trailing = { Icon(imageVector = WalletIcons.Date, contentDescription = null) },
        )
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
        TransactionType.REFUND -> R.string.transaction_type_refund
        TransactionType.BORROW -> R.string.transaction_type_borrow
        TransactionType.LEND -> R.string.transaction_type_lend
        TransactionType.OWED_BEFORE -> R.string.transaction_type_owed_before
        TransactionType.CREDIT_PURCHASE -> R.string.transaction_type_credit_purchase
        TransactionType.EARNED_UNPAID -> R.string.transaction_type_earned_unpaid
    },
)

@Composable
internal fun formAmountLabel(currencyCode: String?): String = currencyCode
    ?.let { stringResource(R.string.field_amount_in_currency, it) } ?: stringResource(R.string.field_amount)

/** The account currency belongs in the amount label: it is what the typed number will be recorded in. */
@Composable
internal fun amountLabel(account: Account?): String = account
    ?.let { stringResource(R.string.field_amount_in_currency, it.currencyCode) }
    ?: stringResource(R.string.field_amount)

@Composable
internal fun accountLabel(account: Account): String =
    stringResource(R.string.account_with_currency, account.name, account.currencyCode)

@OptIn(ExperimentalMaterial3Api::class)
@Preview(name = "Light", showBackground = true, widthDp = 360)
@Preview(name = "Dark", showBackground = true, widthDp = 360, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun AddTransactionPreview(
    @PreviewParameter(AddTransactionPreviewStates::class) state: AddTransactionUiState,
) {
    MyWalletTheme(dynamicColor = false) {
        AddTransactionContent(
            state = state,
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
            onFeeValueChanged = {}, onFeeModeChanged = {}, onFeeCategorySelected = {}, onRetryFeeCategory = {},
        )
    }
}

/** Separate kind pills wrap naturally when labels grow or the screen narrows. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun KindChoices(
    selected: TransactionType,
    onSelect: (TransactionType) -> Unit,
    onMore: () -> Unit,
) {
    FlowRow(
        modifier = Modifier.fillMaxWidth().selectableGroup(),
        horizontalArrangement = Arrangement.spacedBy(WalletTheme.dimensions.relatedGap),
        verticalArrangement = Arrangement.spacedBy(WalletTheme.dimensions.relatedGap),
    ) {
        standardTransactionTypes.forEach { type ->
            TransactionTypePill(type, selected == type, onClick = { onSelect(type) })
        }
        OutlinedButton(
            onClick = onMore,
            modifier = Modifier.heightIn(min = WalletTheme.dimensions.minTouchTarget),
            shape = CircleShape,
            contentPadding = PaddingValues(horizontal = WalletTheme.dimensions.cornerMedium),
        ) {
            Text(stringResource(R.string.kind_more))
        }
    }
}

/** Kept as a standalone selector for large-text and RTL coverage; Add appends More to the same row. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun TransactionTypeSelector(selected: TransactionType, onSelect: (TransactionType) -> Unit) {
    FlowRow(
        modifier = Modifier.fillMaxWidth().selectableGroup(),
        horizontalArrangement = Arrangement.spacedBy(WalletTheme.dimensions.relatedGap),
        verticalArrangement = Arrangement.spacedBy(WalletTheme.dimensions.relatedGap),
    ) {
        standardTransactionTypes.forEach { type ->
            TransactionTypePill(type, selected == type, onClick = { onSelect(type) })
        }
    }
}

@Composable
private fun TransactionTypePill(type: TransactionType, selected: Boolean, onClick: () -> Unit) {
    val container = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface
    val foreground = if (selected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface
    val outline = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.outline
    val tag = if (selected) Modifier.testTag("selected-type-${type.name}") else Modifier
    Box(
        modifier = Modifier
            .heightIn(min = WalletTheme.dimensions.minTouchTarget)
            .clip(CircleShape)
            .background(container)
            .border(1.dp, outline, CircleShape)
            .selectable(selected = selected, onClick = onClick, role = Role.RadioButton)
            .then(tag)
            .padding(horizontal = WalletTheme.dimensions.cornerMedium),
        contentAlignment = Alignment.Center,
    ) {
        Text(typeLabel(type), color = foreground, style = MaterialTheme.typography.labelLarge, maxLines = 1)
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
    errorText: String? = null,
) {
    Column(modifier = modifier.fillMaxWidth()) {
        TextField(
            value = value,
            onValueChange = onValueChange,
            label = { Text(label) },
            isError = errorText != null,
            shape = MaterialTheme.shapes.medium,
            colors = transactionFieldColors(),
            singleLine = true,
            textStyle = MaterialTheme.typography.headlineMedium.copy(fontWeight = FontWeight.Bold),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
            modifier = Modifier.fillMaxWidth().transactionErrorBorder(errorText != null).semantics {
                if (errorText != null) {
                    error(errorText)
                    liveRegion = LiveRegionMode.Polite
                }
            },
        )
        (errorText ?: supportingText)?.let { hint ->
            Text(
                text = hint,
                modifier = Modifier.padding(
                    start = WalletTheme.dimensions.screenPadding,
                    top = WalletTheme.dimensions.relatedGap,
                ),
                color = if (errorText != null) MaterialTheme.colorScheme.error
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}
