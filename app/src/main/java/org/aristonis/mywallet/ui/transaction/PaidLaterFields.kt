package org.aristonis.mywallet.ui.transaction

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardOptions
import org.aristonis.mywallet.R
import org.aristonis.mywallet.domain.model.Currency
import org.aristonis.mywallet.domain.model.Debt
import org.aristonis.mywallet.ui.format.rememberDateFormatter
import org.aristonis.mywallet.ui.format.bidiIsolate
import org.aristonis.mywallet.ui.format.display
import org.aristonis.mywallet.data.format.MoneyFormatter
import androidx.compose.ui.platform.LocalConfiguration
import org.aristonis.mywallet.ui.message.text
import org.aristonis.mywallet.ui.theme.WalletTheme
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

/** The paid-later purchase fields use the same counterparty control as debt entries. */
@Composable
internal fun PaidLaterFields(
    state: PaidLaterFormState, debts: List<Debt>, currencyCode: String?, currencies: List<Currency>,
    onEnabled: (Boolean) -> Unit, onPaidNow: (String) -> Unit, onName: (String) -> Unit,
    onDebt: (Long) -> Unit, onDueDate: (LocalDate?) -> Unit,
) {
    val locale = LocalConfiguration.current.locales[0]
    val formatter = remember(locale) { MoneyFormatter(locale) }
    Column(verticalArrangement = Arrangement.spacedBy(WalletTheme.dimensions.grid)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(stringResource(R.string.paid_later_title))
            Switch(checked = state.enabled, onCheckedChange = onEnabled)
        }
        if (state.enabled) {
            TextField(value = state.paidNowInput, onValueChange = onPaidNow,
                label = { Text(stringResource(R.string.paid_later_paid_now)) },
                isError = state.error != null, singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                colors = transactionFieldColors(), shape = MaterialTheme.shapes.medium,
                modifier = Modifier.fillMaxWidth())
            state.error?.let { Text(it.text(), color = MaterialTheme.colorScheme.error) }
            val offered = state.offeredDebts(debts, currencyCode)
                .filter { it.counterparty.startsWith(state.counterparty.trim(), ignoreCase = true) }
            CounterpartyField(state.counterparty, offered.map { it.counterparty }, onName) { selected ->
                offered.firstOrNull { it.counterparty == selected }?.let { onDebt(it.id) }
            }
            state.owed?.let { Text(stringResource(R.string.paid_later_owed, bidiIsolate(formatter.display(it, currencies)))) }
            PaidLaterDueDate(state.dueDate, onDueDate)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PaidLaterDueDate(date: LocalDate?, onDate: (LocalDate?) -> Unit) {
    var showPicker by remember { mutableStateOf(false) }
    val formatDate = rememberDateFormatter()
    TextButton(onClick = { showPicker = true }) {
        Text(stringResource(R.string.paid_later_due_date))
        if (date != null) Text(stringResource(R.string.paid_later_due_date_value, formatDate(date)))
    }
    if (date != null) TextButton(onClick = { onDate(null) }) { Text(stringResource(R.string.paid_later_clear_date)) }
    if (!showPicker) return
    val picker = rememberDatePickerState(initialSelectedDateMillis = date?.atStartOfDay(ZoneOffset.UTC)?.toInstant()?.toEpochMilli())
    DatePickerDialog(onDismissRequest = { showPicker = false }, confirmButton = {
        TextButton(onClick = {
            picker.selectedDateMillis?.let { onDate(Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate()) }
            showPicker = false
        }) { Text(stringResource(R.string.action_ok)) }
    }, dismissButton = {
        TextButton(onClick = { showPicker = false }) { Text(stringResource(R.string.action_cancel)) }
    }) { DatePicker(state = picker) }
}
