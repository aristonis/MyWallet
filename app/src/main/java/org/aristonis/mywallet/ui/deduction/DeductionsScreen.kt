package org.aristonis.mywallet.ui.deduction

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.aristonis.mywallet.R
import org.aristonis.mywallet.domain.model.DeductionItem
import org.aristonis.mywallet.domain.model.DeductionMode
import org.aristonis.mywallet.domain.model.DeductionTiming
import org.aristonis.mywallet.ui.components.LoadFailedState
import org.aristonis.mywallet.ui.components.WalletTopAppBar
import org.aristonis.mywallet.ui.format.bidiIsolate
import org.aristonis.mywallet.ui.message.text

/** Lists reusable deductions, with editing and history-safe deletion. */
@Composable
fun DeductionsScreen(
    onDone: () -> Unit,
    onAdd: () -> Unit,
    onEdit: (Long) -> Unit,
    viewModel: DeductionsViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var deletingId by rememberSaveable { mutableStateOf<Long?>(null) }
    Scaffold(topBar = { WalletTopAppBar(stringResource(R.string.deductions_title), onBack = onDone) }) { padding ->
        DeductionsList(state, viewModel, onAdd, onEdit, { deletingId = it }, Modifier.padding(padding))
    }
    val deleting = state.items.find { it.item.id == deletingId }?.item
    deleting?.let { item ->
        DeleteDialog(item, onDismiss = { deletingId = null }, onConfirm = {
            deletingId = null
            viewModel.delete(item.id)
        })
    }
    if (state.disableOfferedId != null) DisableDialog(state.isWorking, viewModel)
}

@Composable
private fun DeductionsList(
    state: DeductionsUiState,
    viewModel: DeductionsViewModel,
    onAdd: () -> Unit,
    onEdit: (Long) -> Unit,
    onDelete: (Long) -> Unit,
    modifier: Modifier,
) {
    LazyColumn(modifier.fillMaxSize().padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { ReadStatus(state, viewModel::retry) }
        if (!state.loadFailed && !state.isLoading) {
            items(state.items, key = { it.item.id }) { row ->
                DeductionRow(row, state.isWorking, onEdit, onDelete, viewModel::setEnabled)
            }
        }
        if (state.showAddAction) item {
            Button(onClick = onAdd, enabled = !state.isWorking, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.deduction_add))
            }
        }
        state.error?.let { error -> item { Text(error.text(), color = MaterialTheme.colorScheme.error) } }
    }
}

@Composable
private fun ReadStatus(state: DeductionsUiState, onRetry: () -> Unit) {
    when {
        state.isLoading -> CircularProgressIndicator()
        state.loadFailed -> LoadFailedState(stringResource(R.string.deductions_load_failed), onRetry)
        state.items.isEmpty() -> Text(stringResource(R.string.deductions_empty))
    }
}

@Composable
private fun DeductionRow(
    row: DeductionListRow,
    isWorking: Boolean,
    onEdit: (Long) -> Unit,
    onDelete: (Long) -> Unit,
    onEnabled: (Long, Boolean) -> Unit,
) {
    val item = row.item
    val switchLabel = stringResource(R.string.deduction_enabled_for, bidiIsolate(item.name))
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f).clickable(enabled = !isWorking) { onEdit(item.id) }
                .padding(vertical = 12.dp)) {
                Text(bidiIsolate(item.name), style = MaterialTheme.typography.titleMedium)
                Text(summary(row), style = MaterialTheme.typography.bodyMedium)
            }
            Switch(checked = item.enabled, onCheckedChange = { onEnabled(item.id, it) }, enabled = !isWorking,
                modifier = Modifier.padding(start = 8.dp).semantics { contentDescription = switchLabel })
        }
        TextButton(onClick = { onDelete(item.id) }, enabled = !isWorking) {
            Text(stringResource(R.string.action_delete))
        }
        HorizontalDivider()
    }
}

@Composable
private fun DeleteDialog(item: DeductionItem, onDismiss: () -> Unit, onConfirm: () -> Unit) {
    AlertDialog(onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.deduction_delete_title)) },
        text = { Text(stringResource(R.string.deduction_delete_body, bidiIsolate(item.name))) },
        confirmButton = { TextButton(onClick = onConfirm) { Text(stringResource(R.string.action_delete)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } })
}

@Composable
private fun DisableDialog(isWorking: Boolean, viewModel: DeductionsViewModel) {
    AlertDialog(onDismissRequest = viewModel::dismissDisableOffer,
        title = { Text(stringResource(R.string.deduction_disable_title)) },
        text = { Text(stringResource(R.string.deduction_disable_body)) },
        confirmButton = { TextButton(onClick = viewModel::disableUsedItem, enabled = !isWorking) {
            Text(stringResource(R.string.deduction_disable_action))
        } }, dismissButton = { TextButton(onClick = viewModel::dismissDisableOffer) {
            Text(stringResource(R.string.action_cancel))
        } })
}

@Composable
private fun summary(row: DeductionListRow): String {
    val item = row.item
    val timing = stringResource(if (item.timing == DeductionTiming.NOW) R.string.deduction_now
        else R.string.deduction_later)
    val value = bidiIsolate(row.valueText)
    return if (item.mode == DeductionMode.PERCENT) stringResource(R.string.deduction_percent_summary, value, timing)
    else stringResource(R.string.deduction_fixed_summary, value, bidiIsolate(item.valueCurrencyCode.orEmpty()), timing)
}
