package org.aristonis.mywallet.ui.deduction

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.tooling.preview.Preview
import android.content.res.Configuration
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.aristonis.mywallet.R
import org.aristonis.mywallet.domain.model.DeductionMode
import org.aristonis.mywallet.domain.model.DeductionTiming
import org.aristonis.mywallet.ui.components.AristonisLoader
import org.aristonis.mywallet.ui.components.LabeledDropdown
import org.aristonis.mywallet.ui.components.LoadFailedState
import org.aristonis.mywallet.ui.components.StatusCard
import org.aristonis.mywallet.ui.components.StatusTone
import org.aristonis.mywallet.ui.components.WalletTopAppBar
import org.aristonis.mywallet.ui.format.bidiIsolate
import org.aristonis.mywallet.ui.label
import org.aristonis.mywallet.ui.message.text
import org.aristonis.mywallet.ui.theme.MyWalletTheme
import org.aristonis.mywallet.ui.theme.WalletTheme
import org.aristonis.mywallet.ui.text

/** Creates or edits reusable defaults, leaving saved income snapshots untouched. */
@Composable
fun DeductionEditorScreen(
    isEditing: Boolean,
    onDone: () -> Unit,
    viewModel: DeductionEditorViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    LaunchedEffect(state.saved) { if (state.saved) onDone() }
    Scaffold(topBar = {
        WalletTopAppBar(stringResource(if (isEditing) R.string.deduction_edit else R.string.deduction_add),
            onBack = onDone)
    }) { padding ->
        DeductionEditorContent(
            state = state,
            onNameChange = viewModel::setName,
            onValueChange = viewModel::setValue,
            onModeSelect = viewModel::selectMode,
            onCurrencySelect = viewModel::selectCurrency,
            onTimingSelect = viewModel::selectTiming,
            onExpenseCategorySelect = viewModel::selectExpenseCategory,
            onExpenseSubCategorySelect = viewModel::selectExpenseSubCategory,
            onAutoAddToggle = viewModel::toggleAutoAdd,
            onEnabledChange = viewModel::setEnabled,
            onSubmit = viewModel::submit,
            onRetry = viewModel::retry,
            modifier = Modifier.padding(padding),
        )
    }
}

/** The editor body for a given [state]; it holds no state of its own and reports every action upward. */
@Composable
internal fun DeductionEditorContent(
    state: DeductionEditorUiState,
    onNameChange: (String) -> Unit,
    onValueChange: (String) -> Unit,
    onModeSelect: (DeductionMode) -> Unit,
    onCurrencySelect: (String) -> Unit,
    onTimingSelect: (DeductionTiming) -> Unit,
    onExpenseCategorySelect: (Long?) -> Unit,
    onExpenseSubCategorySelect: (Long?) -> Unit,
    onAutoAddToggle: (Long) -> Unit,
    onEnabledChange: (Boolean) -> Unit,
    onSubmit: () -> Unit,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (state.isLoading) {
        AristonisLoader(modifier.fillMaxSize().imePadding())
        return
    }
    val dimensions = WalletTheme.dimensions
    Column(modifier.fillMaxSize().imePadding().verticalScroll(rememberScrollState())
        .padding(dimensions.screenPadding), verticalArrangement = Arrangement.spacedBy(dimensions.sectionGap)) {
        when {
            state.notFound -> StatusCard(StatusTone.ERROR, stringResource(R.string.msg_deduction_item_not_found))
            state.loadFailed -> LoadFailedState(stringResource(R.string.deductions_load_failed), onRetry)
            else -> {
                Column(verticalArrangement = Arrangement.spacedBy(dimensions.relatedGap)) {
                    DeductionValueFields(state, onNameChange, onValueChange, onModeSelect, onCurrencySelect,
                        onTimingSelect)
                }
                Column(verticalArrangement = Arrangement.spacedBy(dimensions.relatedGap)) {
                    DeductionCategoryFields(state, onExpenseCategorySelect, onExpenseSubCategorySelect, onAutoAddToggle)
                }
                EnabledField(state, onEnabledChange)
                state.error?.let { StatusCard(StatusTone.ERROR, it.text()) }
                Button(onClick = onSubmit, enabled = state.canSubmit,
                    modifier = Modifier.fillMaxWidth().heightIn(min = dimensions.minTouchTarget)) {
                    if (state.isSubmitting) {
                        Row(horizontalArrangement = Arrangement.spacedBy(dimensions.relatedGap),
                            verticalAlignment = Alignment.CenterVertically) {
                            CircularProgressIndicator(modifier = Modifier.size(dimensions.iconSize))
                            Text(stringResource(R.string.management_deduction_saving))
                        }
                    } else {
                        Text(stringResource(R.string.action_save))
                    }
                }
            }
        }
    }
}

@Composable
private fun DeductionValueFields(
    state: DeductionEditorUiState,
    onNameChange: (String) -> Unit,
    onValueChange: (String) -> Unit,
    onModeSelect: (DeductionMode) -> Unit,
    onCurrencySelect: (String) -> Unit,
    onTimingSelect: (DeductionTiming) -> Unit,
) {
    OutlinedTextField(value = state.name, onValueChange = onNameChange,
        label = { Text(stringResource(R.string.deduction_name)) }, singleLine = true,
        isError = state.nameError != null,
        supportingText = { state.nameError?.let { Text(it.text()) } }, modifier = Modifier.fillMaxWidth())
    LabeledDropdown(label = stringResource(R.string.deduction_mode), selectedText = modeLabel(state.mode),
        items = DeductionMode.entries, itemLabel = { modeLabel(it) }, onSelect = onModeSelect)
    OutlinedTextField(value = state.value, onValueChange = onValueChange,
        label = { Text(stringResource(R.string.deduction_value)) }, singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), isError = state.valueError != null,
        suffix = { Text(if (state.showsCurrency) bidiIsolate(state.currencyCode.orEmpty()) else "%") },
        supportingText = { state.valueError?.let { Text(it.text()) } }, modifier = Modifier.fillMaxWidth())
    if (state.showsCurrency) {
        LabeledDropdown(label = stringResource(R.string.deduction_currency),
            selectedText = bidiIsolate(state.currencyCode.orEmpty()), items = state.currencies,
            itemLabel = { bidiIsolate(it.code) }, onSelect = { onCurrencySelect(it.code) })
    }
    LabeledDropdown(label = stringResource(R.string.deduction_timing), selectedText = timingLabel(state.timing),
        items = DeductionTiming.entries, itemLabel = { timingLabel(it) }, onSelect = onTimingSelect)
}

@Composable
private fun DeductionCategoryFields(
    state: DeductionEditorUiState,
    onExpenseCategorySelect: (Long?) -> Unit,
    onExpenseSubCategorySelect: (Long?) -> Unit,
    onAutoAddToggle: (Long) -> Unit,
) {
    val selected = state.expenseCategories.find { it.id == state.expenseCategoryId }
    LabeledDropdown(label = stringResource(R.string.deduction_expense_category),
        selectedText = when {
            selected != null -> bidiIsolate(selected.label().text())
            state.expenseCategoryMissing -> stringResource(R.string.deduction_category_choose)
            else -> stringResource(R.string.deduction_category_automatic)
        },
        items = listOf(null) + state.expenseCategories,
        itemLabel = { it?.let { category -> bidiIsolate(category.label().text()) }
            ?: stringResource(R.string.deduction_category_automatic) },
        onSelect = { onExpenseCategorySelect(it?.id) })
    if (state.expenseCategoryId != null && state.expenseSubCategories.isNotEmpty()) {
        val subCategory = state.expenseSubCategories.find { it.id == state.expenseSubCategoryId }
        LabeledDropdown(label = stringResource(R.string.deduction_expense_subcategory),
            selectedText = subCategory?.let { bidiIsolate(it.label().text()) }
                ?: stringResource(R.string.deduction_subcategory_none),
            items = listOf(null) + state.expenseSubCategories,
            itemLabel = { it?.let { category -> bidiIsolate(category.label().text()) }
                ?: stringResource(R.string.deduction_subcategory_none) },
            onSelect = { onExpenseSubCategorySelect(it?.id) })
    }
    if (state.expenseCategoryMissing) {
        StatusCard(StatusTone.WARNING, stringResource(R.string.deduction_category_missing))
    }
    Text(stringResource(R.string.deduction_auto_add), style = MaterialTheme.typography.titleMedium)
    Text(stringResource(R.string.deduction_auto_add_hint), style = MaterialTheme.typography.bodyMedium)
    state.incomeCategories.forEach { category ->
        val checked = category.id in state.autoAddCategoryIds
        Row(Modifier.fillMaxWidth().toggleable(value = checked, role = Role.Checkbox,
            onValueChange = { onAutoAddToggle(category.id) }).padding(vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Checkbox(checked = checked, onCheckedChange = null)
            Text(bidiIsolate(category.label().text()), modifier = Modifier.weight(1f))
        }
    }
}

@Composable
private fun EnabledField(state: DeductionEditorUiState, onEnabledChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().toggleable(value = state.enabled, role = Role.Switch,
        onValueChange = onEnabledChange).padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Text(stringResource(R.string.deduction_enabled), modifier = Modifier.weight(1f))
        Switch(checked = state.enabled, onCheckedChange = null)
    }
}

@Composable
private fun modeLabel(mode: DeductionMode) = stringResource(
    if (mode == DeductionMode.PERCENT) R.string.deduction_percent else R.string.deduction_fixed)

@Composable
private fun timingLabel(timing: DeductionTiming) = stringResource(
    if (timing == DeductionTiming.NOW) R.string.deduction_now else R.string.deduction_later)

@Preview(name = "Deduction editor light", showBackground = true, widthDp = 360)
@Preview(name = "Deduction editor dark", showBackground = true, widthDp = 360,
    uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun DeductionEditorPreview() {
    MyWalletTheme(dynamicColor = false) {
        DeductionEditorContent(
            state = DeductionEditorUiState(name = "Tax", value = "10", currencyCode = "USD",
                isLoading = false),
            onNameChange = {}, onValueChange = {}, onModeSelect = {}, onCurrencySelect = {},
            onTimingSelect = {}, onExpenseCategorySelect = {}, onExpenseSubCategorySelect = {}, onAutoAddToggle = {},
            onEnabledChange = {}, onSubmit = {}, onRetry = {},
        )
    }
}
