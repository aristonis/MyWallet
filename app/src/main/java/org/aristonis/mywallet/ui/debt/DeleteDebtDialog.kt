package org.aristonis.mywallet.ui.debt

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import org.aristonis.mywallet.R
import org.aristonis.mywallet.domain.model.DebtDeletionPreview
import org.aristonis.mywallet.ui.format.bidiIsolate
import org.aristonis.mywallet.ui.message.UiMessage
import org.aristonis.mywallet.ui.transaction.kindLabel
import org.aristonis.mywallet.ui.transaction.rowType

/** The preview the person confirms; deletion itself always reads the latest history again. */
data class DeleteDebtDialogUiState(
    val preview: DebtDeletionPreview,
    val remainingAfterDisplay: String,
    val error: UiMessage? = null,
)

/** Lists what is removed and retained before offering the guarded whole-debt delete. */
@Composable
internal fun DeleteDebtDialog(state: DeleteDebtDialogUiState, isSubmitting: Boolean,
    onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(onDismissRequest = { if (!isSubmitting) onDismiss() },
        title = { Text(stringResource(R.string.debt_delete_title, bidiIsolate(state.preview.debt.counterparty))) },
        text = { DeleteDebtPreview(state) },
        confirmButton = {
            TextButton(onClick = onConfirm, enabled = !isSubmitting) { Text(stringResource(R.string.debt_delete)) }
        }, dismissButton = {
            TextButton(onClick = onDismiss, enabled = !isSubmitting) { Text(stringResource(R.string.action_cancel)) }
        })
}

@Composable
private fun DeleteDebtPreview(state: DeleteDebtDialogUiState) {
    val removed = state.preview.entries.size
    val retained = state.preview.retainedEntryIds.size
    Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(pluralStringResource(R.plurals.debt_delete_removed, removed, removed))
        Text(pluralStringResource(R.plurals.debt_delete_retained, retained, retained))
        Text(stringResource(R.string.debt_delete_remaining, bidiIsolate(state.remainingAfterDisplay)))
        state.preview.entries.forEach { entry ->
            Text(entry.note?.takeIf(String::isNotBlank) ?: kindLabel(entry.rowType()))
        }
        state.error?.let { DebtActionError(it) }
    }
}
