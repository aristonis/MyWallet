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
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.aristonis.mywallet.R
import org.aristonis.mywallet.domain.model.DebtSide
import org.aristonis.mywallet.domain.model.DebtState
import org.aristonis.mywallet.domain.model.DebtTotals
import org.aristonis.mywallet.ui.components.LoadFailedState
import org.aristonis.mywallet.ui.components.MoneyText
import org.aristonis.mywallet.ui.components.WalletListRow
import org.aristonis.mywallet.ui.components.WalletTopAppBar
import org.aristonis.mywallet.ui.icons.WalletIcons
import org.aristonis.mywallet.ui.message.UiMessage
import org.aristonis.mywallet.ui.message.text
import org.aristonis.mywallet.ui.window.RefreshOnStart

@Composable
fun DebtsScreen(onDone: () -> Unit, onOpenDebt: (Long) -> Unit, onManageRates: () -> Unit,
    viewModel: DebtsViewModel = hiltViewModel()) {
    RefreshOnStart(viewModel::refreshToday)
    val state by viewModel.state.collectAsStateWithLifecycle()
    DebtsContent(state, onDone, onOpenDebt, onManageRates, viewModel::selectSide, viewModel::retry)
}

@Composable
internal fun DebtsContent(state: DebtsUiState, onDone: () -> Unit, onOpenDebt: (Long) -> Unit,
    onManageRates: () -> Unit, onSelectSide: (DebtSide) -> Unit, onRetry: () -> Unit) {
    BackHandler(onBack = onDone)
    Scaffold(topBar = { WalletTopAppBar(stringResource(R.string.debts_title), onBack = onDone) }) { padding ->
        LazyColumn(modifier = Modifier.padding(padding).fillMaxSize(), contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item { DebtSides(state.selectedSide, onSelectSide) }
            when {
                state.isLoading -> item { CircularProgressIndicator() }
                state.loadFailed -> item { LoadFailedState((state.loadError ?: UiMessage.DataUnreadable).text(), onRetry) }
                else -> {
                    item { DebtTotalsCard(state, onManageRates) }
                    if (state.open.isEmpty() && state.settled.isEmpty()) item { Text(stringResource(R.string.debts_empty)) }
                    items(state.open, key = { it.summary.debt.id }) { DebtListRow(it, onOpenDebt) }
                    if (state.settled.isNotEmpty()) item { Text(stringResource(R.string.debts_settled), style = MaterialTheme.typography.titleMedium) }
                    items(state.settled, key = { it.summary.debt.id }) { DebtListRow(it, onOpenDebt) }
                }
            }
        }
    }
}

@Composable
private fun DebtSides(selected: DebtSide, onSelectSide: (DebtSide) -> Unit) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        FilterChip(modifier = Modifier.weight(1f), selected = selected == DebtSide.I_OWE, onClick = { onSelectSide(DebtSide.I_OWE) },
            label = { Text(stringResource(R.string.debts_i_owe)) })
        FilterChip(modifier = Modifier.weight(1f), selected = selected == DebtSide.OWED_TO_ME, onClick = { onSelectSide(DebtSide.OWED_TO_ME) },
            label = { Text(stringResource(R.string.debts_owed_to_me)) })
    }
}

@Composable
private fun DebtTotalsCard(state: DebtsUiState, onManageRates: () -> Unit) {
    val missing = state.totals as? DebtTotals.MissingRate
    if (missing != null) {
        Column {
            Text(stringResource(R.string.debts_missing_rate, missing.currencyCode), color = MaterialTheme.colorScheme.error)
            TextButton(onClick = onManageRates) { Text(stringResource(R.string.debts_manage_rates)) }
        }
        return
    }
    val amount = if (state.selectedSide == DebtSide.I_OWE) state.iOweDisplay else state.owedToMeDisplay
    if (amount != null) MoneyText(amount, style = MaterialTheme.typography.headlineMedium)
}

@Composable
private fun DebtListRow(row: DebtRow, onOpenDebt: (Long) -> Unit) {
    // Stacking the amount below the name leaves room for both at large font sizes.
    Column(modifier = Modifier.fillMaxWidth()) {
        WalletListRow(headline = row.summary.debt.counterparty, supporting = row.remainingDisplay,
            leadingIcon = if (row.summary.state == DebtState.OVERDUE || row.summary.remaining.isNegative) WalletIcons.Warning else WalletIcons.OwedBefore,
            onClick = { onOpenDebt(row.summary.debt.id) })
        DebtStateLabel(row.summary.state)
        if (row.summary.remaining.isNegative) Text(stringResource(R.string.debts_negative), color = MaterialTheme.colorScheme.error)
    }
}

@Composable
internal fun DebtStateLabel(state: DebtState) {
    val label = when (state) {
        DebtState.OPEN -> R.string.debts_open
        DebtState.OVERDUE -> R.string.debts_overdue
        DebtState.SETTLED -> R.string.debts_settled
        DebtState.HIDDEN -> R.string.debts_hidden
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(horizontal = 16.dp)) {
        if (state == DebtState.OVERDUE) Icon(WalletIcons.Warning, contentDescription = null, tint = MaterialTheme.colorScheme.error)
        Text(stringResource(label), style = MaterialTheme.typography.labelMedium)
    }
}
