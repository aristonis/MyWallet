package org.aristonis.mywallet.ui.transaction

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Checkbox
import androidx.compose.material3.TextButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import org.aristonis.mywallet.R
import org.aristonis.mywallet.domain.model.Currency
import org.aristonis.mywallet.domain.model.DeductionMode
import org.aristonis.mywallet.domain.model.DeductionTiming
import org.aristonis.mywallet.ui.components.LabeledDropdown
import org.aristonis.mywallet.ui.format.bidiIsolate
import org.aristonis.mywallet.ui.message.text

/**
 * Income-only entry controls. Every change is sent as a named [DeductionEdit] for one row; the form
 * decides what it means for the money, so this only renders [state] and reports taps.
 */
@Composable
internal fun DeductionsSection(
    state: IncomeDeductionFormState,
    currencies: List<Currency>,
    currency: String?,
    onEdit: (itemId: Long, edit: DeductionEdit) -> Unit,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(R.string.income_deductions_title), style = MaterialTheme.typography.titleMedium)
        val catalogFailed = state.catalogError != null
        if (state.itemsLoading) Text(stringResource(R.string.income_deductions_loading))
        else if (state.drafts.isEmpty() && !catalogFailed) Text(stringResource(R.string.income_deductions_empty))
        if (catalogFailed) TextButton(onClick = onRetry) { Text(stringResource(R.string.action_retry)) }
        state.drafts.forEach { row ->
            DeductionEntryFields(row, state.convertedInputs[row.itemId], currencies, currency) { edit ->
                onEdit(row.itemId, edit)
            }
        }
        state.error?.let { Text(it.text(), color = MaterialTheme.colorScheme.error) }
        state.summary?.let { DeductionSummaryCard(it, currencies) }
    }
}

@Composable
private fun DeductionEntryFields(
    row: IncomeDeductionDraft, converted: String?, currencies: List<Currency>,
    currency: String?, onEdit: (DeductionEdit) -> Unit,
) {
    Column(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Column(Modifier.fillMaxWidth().toggleable(row.selected, role = Role.Checkbox,
            onValueChange = { onEdit(DeductionEdit.SetSelected(it)) }).padding(8.dp)) {
            Text(bidiIsolate(row.name), style = MaterialTheme.typography.titleSmall)
            Checkbox(checked = row.selected, onCheckedChange = null)
        }
        if (!row.selected) return@Column
        LabeledDropdown(stringResource(R.string.income_deduction_value_type), modeLabel(row.mode),
            DeductionMode.entries, { modeLabel(it) }, { onEdit(DeductionEdit.SetMode(it)) })
        DecimalField(row.valueInput, stringResource(R.string.income_deduction_value)) {
            onEdit(DeductionEdit.SetValue(it))
        }
        if (row.mode == DeductionMode.FIXED) {
            LabeledDropdown(stringResource(R.string.income_deduction_currency),
                bidiIsolate(row.valueCurrencyCode ?: currency.orEmpty()),
                currencies, { bidiIsolate(it.code) }, { onEdit(DeductionEdit.SetCurrency(it.code)) })
        }
        converted?.let { text ->
            DecimalField(text, stringResource(R.string.income_deduction_converted, bidiIsolate(currency.orEmpty()))) {
                onEdit(DeductionEdit.SetConverted(it))
            }
        }
        Text(timingLabel(row.timing), style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun DecimalField(value: String, label: String, onValueChange: (String) -> Unit) {
    OutlinedTextField(value = value, onValueChange = onValueChange, label = { Text(label) }, singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), modifier = Modifier.fillMaxWidth())
}

@Composable
private fun modeLabel(mode: DeductionMode) = stringResource(
    if (mode == DeductionMode.PERCENT) R.string.income_deduction_percent else R.string.income_deduction_fixed)

@Composable
private fun timingLabel(timing: DeductionTiming) = stringResource(
    if (timing == DeductionTiming.NOW) R.string.income_deduction_now else R.string.income_deduction_later)
