package org.aristonis.mywallet.ui.transaction

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import org.aristonis.mywallet.R
import org.aristonis.mywallet.domain.model.Currency
import org.aristonis.mywallet.domain.model.DebtSide
import org.aristonis.mywallet.ui.components.LabeledDropdown
import org.aristonis.mywallet.ui.components.SearchableCurrencyField

/** The person-or-business field, with the earlier names matching what is typed offered below it. */
@Composable
internal fun CounterpartyField(value: String, suggestions: List<String>, onValueChanged: (String) -> Unit,
    onSuggestionSelected: (String) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        OutlinedTextField(value, onValueChanged, label = { Text(stringResource(R.string.kind_counterparty)) },
            singleLine = true, modifier = Modifier.fillMaxWidth())
        suggestions.forEach { name ->
            TextButton(
                onClick = { onSuggestionSelected(name) },
                modifier = Modifier.fillMaxWidth().heightIn(min = MIN_TOUCH_TARGET),
            ) {
                Text(name, modifier = Modifier.fillMaxWidth())
            }
        }
    }
}

/** The fields the debt kinds add to the form: counterparty, currency and side, each only when the kind needs it. */
@Composable
internal fun DebtKindFields(type: TransactionType, inputs: EntryFormInputs, currencies: List<Currency>,
    suggestions: List<String>, onCounterpartyChanged: (String) -> Unit, onSuggestionSelected: (String) -> Unit,
    onCurrencySelected: (String) -> Unit, onDebtSideSelected: (DebtSide) -> Unit) {
    val rule = EntryFormRules.forType(type)
    if (rule.requiresCounterparty) {
        CounterpartyField(inputs.counterparty, suggestions, onCounterpartyChanged, onSuggestionSelected)
    }
    if (rule.requiresCurrency) {
        SearchableCurrencyField(
            label = stringResource(R.string.field_currency),
            selected = currencies.firstOrNull { it.code == inputs.currencyCode },
            currencies = currencies,
            onSelect = onCurrencySelected,
        )
    }
    if (rule.showsDebtSide) {
        LabeledDropdown(
            label = stringResource(R.string.kind_debt_side),
            selectedText = sideLabel(inputs.debtSide),
            items = DebtSide.entries,
            itemLabel = { sideLabel(it) },
            onSelect = onDebtSideSelected,
        )
    }
}

private val MIN_TOUCH_TARGET = 48.dp

@Composable
private fun sideLabel(side: DebtSide) = stringResource(
    if (side == DebtSide.I_OWE) R.string.debts_i_owe else R.string.debts_owed_to_me)
