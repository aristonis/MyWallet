package org.aristonis.mywallet.ui.debt

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import org.aristonis.mywallet.R
import org.aristonis.mywallet.ui.format.rememberDateFormatter
import org.aristonis.mywallet.ui.window.toUtcMillis
import org.aristonis.mywallet.ui.window.utcMillisToDate
import java.time.LocalDate

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun DebtDueDateField(dueDate: LocalDate?, enabled: Boolean, onChange: (LocalDate?) -> Unit) {
    var choosing by rememberSaveable { mutableStateOf(false) }
    val formatDate = rememberDateFormatter()
    Column {
        Text(stringResource(R.string.debt_due_date))
        OutlinedButton(onClick = { choosing = true }, enabled = enabled, modifier = Modifier.fillMaxWidth()) {
            Text(dueDate?.let(formatDate) ?: stringResource(R.string.debt_due_date_none))
        }
        if (dueDate != null) TextButton(onClick = { onChange(null) }, enabled = enabled) { Text(stringResource(R.string.debt_clear_due_date)) }
    }
    if (choosing) {
        val picker = rememberDatePickerState(initialSelectedDateMillis = (dueDate ?: LocalDate.now()).toUtcMillis())
        DatePickerDialog(onDismissRequest = { choosing = false }, confirmButton = {
            TextButton(onClick = {
                picker.selectedDateMillis?.let { onChange(utcMillisToDate(it)) }
                choosing = false
            }) { Text(stringResource(R.string.action_ok)) }
        }, dismissButton = { TextButton(onClick = { choosing = false }) { Text(stringResource(R.string.action_cancel)) } }) {
            DatePicker(picker)
        }
    }
}
