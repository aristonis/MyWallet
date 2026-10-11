package org.aristonis.mywallet.ui.debt

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.aristonis.mywallet.R
import org.aristonis.mywallet.ui.components.LoadFailedState
import org.aristonis.mywallet.ui.components.MoneyText
import org.aristonis.mywallet.ui.components.WalletListRow
import org.aristonis.mywallet.ui.components.WalletTopAppBar
import org.aristonis.mywallet.ui.format.rememberDateFormatter
import org.aristonis.mywallet.ui.icons.WalletIcons
import org.aristonis.mywallet.ui.message.UiMessage
import org.aristonis.mywallet.ui.message.text
import org.aristonis.mywallet.ui.transaction.kindLabel
import org.aristonis.mywallet.ui.transaction.rowType
import org.aristonis.mywallet.ui.window.RefreshOnStart
import java.time.LocalDate

/** One debt: its balance, editable name, due date and note, and the entries that make it up. */
@Composable
fun DebtDetailScreen(
    debtId: Long,
    onDone: () -> Unit,
    onEditEntry: (Long) -> Unit,
    viewModel: DebtDetailViewModel = hiltViewModel(),
) {
    LaunchedEffect(debtId) { viewModel.load(debtId) }
    RefreshOnStart(viewModel::refreshToday)
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    DebtDeletionUndo(state, snackbar, viewModel::undoDeleteDebt, viewModel::confirmDeleted, onDone)
    DebtDetailContent(
        state, onDone, onEditEntry, viewModel::setCounterparty, viewModel::rename,
        viewModel::setDueDate, viewModel::setNote, viewModel::saveDetails, viewModel::retry, viewModel::today,
        viewModel::openWriteOff, viewModel::openDeleteDebt, snackbar,
    )
    state.writeOffDialog?.let { dialog ->
        WriteOffDialog(dialog, state.isSubmitting, viewModel::selectWriteOffCategory, viewModel::setWriteOffDate,
            viewModel::confirmWriteOff, viewModel::dismissWriteOff, viewModel::today)
    }
    state.deleteDialog?.let { dialog ->
        DeleteDebtDialog(dialog, state.isSubmitting, viewModel::confirmDeleteDebt, viewModel::dismissDeleteDebt)
    }
}

@Composable
internal fun DebtDetailContent(
    state: DebtDetailUiState,
    onDone: () -> Unit,
    onEditEntry: (Long) -> Unit,
    onCounterpartyChange: (String) -> Unit,
    onRename: () -> Unit,
    onDueDateChange: (LocalDate?) -> Unit,
    onNoteChange: (String) -> Unit,
    onSaveDetails: () -> Unit,
    onRetry: () -> Unit,
    today: () -> LocalDate,
    onWriteOff: () -> Unit = {},
    onDeleteDebt: () -> Unit = {},
    snackbar: SnackbarHostState = remember { SnackbarHostState() },
) {
    BackHandler(onBack = onDone)
    val dateDisplay = rememberDateFormatter()
    Scaffold(topBar = {
        WalletTopAppBar(stringResource(R.string.debt_detail_title), onBack = onDone, actions = {
            DebtOverflow(state, onDeleteDebt)
        })
    }, snackbarHost = { SnackbarHost(snackbar) }) { padding ->
        LazyColumn(
            modifier = Modifier.padding(padding).fillMaxSize(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            when {
                state.isLoading -> item { CircularProgressIndicator() }
                state.loadFailed -> item {
                    LoadFailedState((state.loadError ?: UiMessage.DataUnreadable).text(), onRetry)
                }
                state.detail == null -> item { Text(UiMessage.DebtNotFound.text()) }
                else -> {
                    item {
                        MoneyText(state.remainingDisplay)
                        DebtStateLabel(state.detail.summary.state)
                    }
                    item { DebtNameField(state, onCounterpartyChange, onRename) }
                    item { DebtDueDateField(state.dueDate, state.canEditMetadata, today, onDueDateChange) }
                    item {
                        OutlinedTextField(
                            state.noteInput, onNoteChange, modifier = Modifier.fillMaxWidth(),
                            label = { Text(stringResource(R.string.debt_note)) }, enabled = state.canEditMetadata,
                        )
                    }
                    item {
                        Button(onClick = onSaveDetails, enabled = state.canEditMetadata) {
                            Text(stringResource(R.string.debt_save_details))
                        }
                    }
                    if (state.error != null && !state.error.isNameError()) {
                        item { Text(state.error.text(), Modifier.semantics { liveRegion = LiveRegionMode.Polite }) }
                    }
                    if (state.detail.summary.remaining.isPositive) item {
                        TextButton(onClick = onWriteOff, enabled = state.canEditMetadata) {
                            Text(stringResource(R.string.debt_write_off_rest))
                        }
                    }
                    if (state.undoableDelete != null && state.error != null) item {
                        Text(stringResource(R.string.debt_undo_failed))
                    }
                    item { Text(stringResource(R.string.debt_history)) }
                    items(state.detail.entries, key = { it.id }) { entry ->
                        WalletListRow(
                            headline = entry.note?.takeIf(String::isNotBlank) ?: kindLabel(entry.rowType()),
                            supporting = debtHistorySupporting(
                                kindLabel(entry.rowType()), dateDisplay(entry.date), state.historyAmounts[entry.id],
                            ),
                            onClick = { onEditEntry(entry.id) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun DebtNameField(state: DebtDetailUiState, onChange: (String) -> Unit, onRename: () -> Unit) {
    val error = state.error?.takeIf { it.isNameError() }
    Column {
        OutlinedTextField(
            state.counterpartyInput, onChange, modifier = Modifier.fillMaxWidth(),
            label = { Text(stringResource(R.string.debt_counterparty)) }, enabled = state.canEditMetadata,
            isError = error != null,
            supportingText = error?.let { message ->
                { Text(message.text(), Modifier.semantics { liveRegion = LiveRegionMode.Polite }) }
            },
        )
        TextButton(onClick = onRename, enabled = state.canEditMetadata) { Text(stringResource(R.string.debt_rename)) }
    }
}

private val DebtDetailUiState.canEditMetadata: Boolean
    get() = detail != null && !loadFailed && !deleted && !isSubmitting && undoableDelete == null &&
        writeOffDialog == null && deleteDialog == null

private fun UiMessage.isNameError(): Boolean = this is UiMessage.DebtNameTaken || this == UiMessage.NameBlank

@Composable
private fun DebtOverflow(state: DebtDetailUiState, onDelete: () -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    IconButton(onClick = { expanded = true },
        enabled = state.canEditMetadata) {
        Icon(WalletIcons.More, contentDescription = stringResource(R.string.debt_more_options))
    }
    DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
        DropdownMenuItem(text = { Text(stringResource(R.string.debt_delete)) }, onClick = {
            expanded = false
            onDelete()
        })
    }
}

@Composable
private fun DebtDeletionUndo(state: DebtDetailUiState, snackbar: SnackbarHostState,
    onUndo: () -> Unit, onConfirm: () -> Unit, onDone: () -> Unit) {
    val deleted = stringResource(R.string.debt_deleted)
    val undo = stringResource(R.string.action_undo)
    val failed = stringResource(R.string.debt_undo_failed)
    LaunchedEffect(state.deleted) { if (state.deleted) onDone() }
    val isReady = state.detail != null && !state.isLoading && !state.loadFailed
    LaunchedEffect(state.undoableDelete, state.error, state.isSubmitting, isReady) {
        if (state.undoableDelete == null || state.isSubmitting || !isReady) return@LaunchedEffect
        val retrying = state.error != null
        val result = snackbar.showSnackbar(if (retrying) failed else deleted, actionLabel = undo,
            duration = if (retrying) SnackbarDuration.Indefinite else SnackbarDuration.Short)
        when (result) {
            SnackbarResult.ActionPerformed -> onUndo()
            SnackbarResult.Dismissed -> if (!retrying) onConfirm()
        }
    }
}
