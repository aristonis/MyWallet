package org.aristonis.mywallet.ui.debt

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import org.aristonis.mywallet.R
import org.aristonis.mywallet.domain.model.Category
import org.aristonis.mywallet.ui.components.LabeledDropdown
import org.aristonis.mywallet.ui.format.rememberDateFormatter
import org.aristonis.mywallet.ui.label
import org.aristonis.mywallet.ui.message.UiMessage
import org.aristonis.mywallet.ui.message.text
import org.aristonis.mywallet.ui.text
import org.aristonis.mywallet.ui.window.toUtcMillis
import org.aristonis.mywallet.ui.window.utcMillisToDate
import java.time.LocalDate

/** Choices for settling the current rest as loss or forgiveness, with a required category. */
data class WriteOffDialogUiState(
    val categories: List<Category>,
    val selectedCategoryId: Long?,
    val date: LocalDate,
    val remainingDisplay: String,
    val error: UiMessage? = null,
)

/** Confirms the rest without an account movement; its category and date remain editable until submission. */
@Composable
internal fun WriteOffDialog(state: WriteOffDialogUiState, isSubmitting: Boolean,
    onCategorySelected: (Long) -> Unit, onDateSelected: (LocalDate) -> Unit,
    onConfirm: () -> Unit, onDismiss: () -> Unit, today: () -> LocalDate) {
    AlertDialog(
        onDismissRequest = { if (!isSubmitting) onDismiss() },
        title = { Text(stringResource(R.string.debt_write_off_rest)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(state.remainingDisplay)
                WriteOffCategory(state, isSubmitting, onCategorySelected)
                WriteOffDate(state.date, isSubmitting, today, onDateSelected)
                state.error?.let { DebtActionError(it) }
            }
        },
        confirmButton = {
            TextButton(onClick = onConfirm, enabled = !isSubmitting && state.selectedCategoryId != null) {
                Text(stringResource(R.string.debt_write_off))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !isSubmitting) { Text(stringResource(R.string.action_cancel)) }
        },
    )
}

@Composable
private fun WriteOffCategory(state: WriteOffDialogUiState, isSubmitting: Boolean, onSelect: (Long) -> Unit) {
    val selected = state.categories.firstOrNull { it.id == state.selectedCategoryId }
    if (isSubmitting) {
        Column {
            Text(stringResource(R.string.field_category))
            OutlinedButton(onClick = {}, enabled = false, modifier = Modifier.fillMaxWidth()) {
                Text(selected?.label()?.text() ?: stringResource(R.string.value_select_prompt))
            }
        }
    } else {
        LabeledDropdown(label = stringResource(R.string.field_category),
            selectedText = selected?.label()?.text() ?: stringResource(R.string.value_select_prompt),
            items = state.categories, itemLabel = { it.label().text() }, onSelect = { onSelect(it.id) })
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun WriteOffDate(date: LocalDate, isSubmitting: Boolean, today: () -> LocalDate,
    onSelect: (LocalDate) -> Unit) {
    var choosing by remember { mutableStateOf(false) }
    val formatDate = rememberDateFormatter()
    Column {
        Text(stringResource(R.string.field_date))
        OutlinedButton(onClick = { choosing = true }, enabled = !isSubmitting, modifier = Modifier.fillMaxWidth()) {
            Text(formatDate(date))
        }
    }
    if (!choosing) return
    val picker = rememberDatePickerState(initialSelectedDateMillis = date.toUtcMillis() ?: today().toUtcMillis())
    DatePickerDialog(onDismissRequest = { if (!isSubmitting) choosing = false }, confirmButton = {
        TextButton(onClick = {
            picker.selectedDateMillis?.let { onSelect(utcMillisToDate(it)) }
            choosing = false
        }, enabled = !isSubmitting) { Text(stringResource(R.string.action_ok)) }
    }, dismissButton = {
        TextButton(onClick = { choosing = false }, enabled = !isSubmitting) {
            Text(stringResource(R.string.action_cancel))
        }
    }) { DatePicker(picker) }
}

/** Announces a newly appearing action refusal without interrupting the current announcement. */
@Composable
internal fun DebtActionError(message: UiMessage) {
    Text(message.text(), Modifier.semantics { liveRegion = LiveRegionMode.Polite },
        color = MaterialTheme.colorScheme.error)
}
