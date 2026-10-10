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
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
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
    DebtDetailContent(
        state, onDone, onEditEntry, viewModel::setCounterparty, viewModel::rename,
        viewModel::setDueDate, viewModel::setNote, viewModel::saveDetails, viewModel::retry, viewModel::today,
    )
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
) {
    BackHandler(onBack = onDone)
    val dateDisplay = rememberDateFormatter()
    Scaffold(topBar = { WalletTopAppBar(stringResource(R.string.debt_detail_title), onBack = onDone) }) { padding ->
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
                    item { DebtDueDateField(state.dueDate, !state.isSubmitting, today, onDueDateChange) }
                    item {
                        OutlinedTextField(
                            state.noteInput, onNoteChange, modifier = Modifier.fillMaxWidth(),
                            label = { Text(stringResource(R.string.debt_note)) }, enabled = !state.isSubmitting,
                        )
                    }
                    item {
                        Button(onClick = onSaveDetails, enabled = !state.isSubmitting) {
                            Text(stringResource(R.string.debt_save_details))
                        }
                    }
                    if (state.error != null && !state.error.isNameError()) {
                        item { Text(state.error.text(), Modifier.semantics { liveRegion = LiveRegionMode.Polite }) }
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
            label = { Text(stringResource(R.string.debt_counterparty)) }, enabled = !state.isSubmitting,
            isError = error != null,
            supportingText = error?.let { message ->
                { Text(message.text(), Modifier.semantics { liveRegion = LiveRegionMode.Polite }) }
            },
        )
        TextButton(onClick = onRename, enabled = !state.isSubmitting) { Text(stringResource(R.string.debt_rename)) }
    }
}

private fun UiMessage.isNameError(): Boolean = this is UiMessage.DebtNameTaken || this == UiMessage.NameBlank
