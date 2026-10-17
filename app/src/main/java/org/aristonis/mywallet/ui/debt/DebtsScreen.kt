package org.aristonis.mywallet.ui.debt

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.aristonis.mywallet.ui.components.AristonisLoader
import org.aristonis.mywallet.R
import org.aristonis.mywallet.domain.model.DebtSide
import org.aristonis.mywallet.domain.model.DebtState
import org.aristonis.mywallet.domain.model.DebtTotals
import org.aristonis.mywallet.ui.components.LoadFailedState
import org.aristonis.mywallet.ui.components.MoneyText
import org.aristonis.mywallet.ui.components.SectionHeader
import org.aristonis.mywallet.ui.components.StatusCard
import org.aristonis.mywallet.ui.components.StatusTone
import org.aristonis.mywallet.ui.components.WalletListRow
import org.aristonis.mywallet.ui.components.WalletTopAppBar
import org.aristonis.mywallet.ui.format.rememberDateFormatter
import org.aristonis.mywallet.ui.icons.WalletIcons
import org.aristonis.mywallet.ui.message.UiMessage
import org.aristonis.mywallet.ui.message.text
import org.aristonis.mywallet.ui.theme.WalletTheme
import org.aristonis.mywallet.ui.window.RefreshOnStart

private const val SUMMARY_WASH_ALPHA = 0.14f
private const val SUMMARY_ICON_BACKGROUND_ALPHA = 0.16f
private val SUMMARY_ICON_BADGE_SIZE = 40.dp

/** The debts list: one side at a time, with the total and each open and settled debt. */
@Composable
fun DebtsScreen(
    onDone: () -> Unit,
    onOpenDebt: (Long) -> Unit,
    onManageRates: () -> Unit,
    onAddDebt: (DebtSide) -> Unit,
    viewModel: DebtsViewModel = hiltViewModel(),
) {
    RefreshOnStart(viewModel::refreshToday)
    val state by viewModel.state.collectAsStateWithLifecycle()
    DebtsContent(state, onDone, onOpenDebt, onManageRates, viewModel::selectSide, viewModel::retry, onAddDebt)
}

@Composable
internal fun DebtsContent(
    state: DebtsUiState,
    onDone: () -> Unit,
    onOpenDebt: (Long) -> Unit,
    onManageRates: () -> Unit,
    onSelectSide: (DebtSide) -> Unit,
    onRetry: () -> Unit,
    onAddDebt: (DebtSide) -> Unit,
) {
    BackHandler(onBack = onDone)
    Scaffold(topBar = { WalletTopAppBar(stringResource(R.string.debts_title), onBack = onDone) }) { padding ->
        if (state.isLoading) {
            Column(
                modifier = Modifier.padding(padding).fillMaxSize()
                    .padding(WalletTheme.dimensions.screenPadding),
                verticalArrangement = Arrangement.spacedBy(WalletTheme.dimensions.regionGap),
            ) {
                DebtSides(state.selectedSide, onSelectSide)
                AristonisLoader(Modifier.weight(1f).fillMaxWidth())
            }
            return@Scaffold
        }
        LazyColumn(
            modifier = Modifier.padding(padding).fillMaxSize(),
            contentPadding = PaddingValues(WalletTheme.dimensions.screenPadding),
            verticalArrangement = Arrangement.spacedBy(WalletTheme.dimensions.regionGap),
        ) {
            item { DebtSides(state.selectedSide, onSelectSide) }
            when {
                state.loadFailed -> item {
                    LoadFailedState((state.loadError ?: UiMessage.DataUnreadable).text(), onRetry)
                }
                else -> debtRows(state, onOpenDebt, onManageRates, onAddDebt)
            }
        }
    }
}

private fun LazyListScope.debtRows(state: DebtsUiState, onOpenDebt: (Long) -> Unit,
    onManageRates: () -> Unit, onAddDebt: (DebtSide) -> Unit) {
    item { DebtTotalsCard(state, onManageRates) }
    item { SectionHeader(stringResource(R.string.debts_active_w2)) }
    if (state.open.isEmpty()) item { Text(stringResource(R.string.debts_empty)) }
    items(state.open, key = { it.summary.debt.id }) { row ->
        DebtListRow(row, onOpenDebt)
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
    }
    if (state.settled.isNotEmpty()) {
        item { SectionHeader(stringResource(R.string.debts_settled)) }
    }
    items(state.settled, key = { it.summary.debt.id }) { row ->
        DebtListRow(row, onOpenDebt)
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
    }
    item {
        Button(onClick = { onAddDebt(state.selectedSide) }, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.debts_add_w2))
        }
    }
}

@Composable
private fun DebtSides(selected: DebtSide, onSelectSide: (DebtSide) -> Unit) {
    Row(modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(WalletTheme.dimensions.relatedGap)) {
        FilterChip(
            modifier = Modifier.weight(1f),
            selected = selected == DebtSide.I_OWE,
            onClick = { onSelectSide(DebtSide.I_OWE) },
            label = { Text(stringResource(R.string.debts_i_owe)) },
        )
        FilterChip(
            modifier = Modifier.weight(1f),
            selected = selected == DebtSide.OWED_TO_ME,
            onClick = { onSelectSide(DebtSide.OWED_TO_ME) },
            label = { Text(stringResource(R.string.debts_owed_to_me)) },
        )
    }
}

@Composable
private fun DebtTotalsCard(state: DebtsUiState, onManageRates: () -> Unit) {
    val missing = state.totals as? DebtTotals.MissingRate
    if (missing != null) {
        Column {
            StatusCard(StatusTone.WARNING, stringResource(R.string.debts_missing_rate, missing.currencyCode))
            TextButton(onClick = onManageRates) { Text(stringResource(R.string.debts_manage_rates)) }
        }
        return
    }
    val amount = if (state.selectedSide == DebtSide.I_OWE) state.iOweDisplay else state.owedToMeDisplay
    if (amount != null) {
        val isIOwe = state.selectedSide == DebtSide.I_OWE
        val roleColor = if (isIOwe) WalletTheme.colors.expense else WalletTheme.colors.income
        val sideIcon = if (isIOwe) WalletIcons.Borrow else WalletIcons.Lend
        val sideTitle = stringResource(if (isIOwe) R.string.debts_i_owe else R.string.debts_owed_to_me)
        val scheme = MaterialTheme.colorScheme
        val wash = Brush.verticalGradient(
            listOf(roleColor.copy(alpha = SUMMARY_WASH_ALPHA), Color.Transparent),
        )
        Card(
            shape = MaterialTheme.shapes.large,
            colors = CardDefaults.cardColors(
                containerColor = scheme.surfaceContainer,
                contentColor = scheme.onSurface,
            ),
            border = BorderStroke(1.dp, scheme.outlineVariant),
        ) {
            Column(
                Modifier.fillMaxWidth().background(wash).padding(WalletTheme.dimensions.cardPadding),
                verticalArrangement = Arrangement.spacedBy(WalletTheme.dimensions.relatedGap),
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(WalletTheme.dimensions.iconContentGap),
                ) {
                    Box(
                        modifier = Modifier.size(SUMMARY_ICON_BADGE_SIZE).clip(CircleShape)
                            .background(roleColor.copy(alpha = SUMMARY_ICON_BACKGROUND_ALPHA)),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(sideIcon, contentDescription = null, tint = roleColor)
                    }
                    Text(sideTitle, style = MaterialTheme.typography.titleMedium)
                }
                Text(stringResource(R.string.debts_remaining_w2),
                    style = MaterialTheme.typography.bodyMedium,
                    color = scheme.onSurfaceVariant)
                MoneyText(
                    amount,
                    style = MaterialTheme.typography.headlineMedium.copy(fontWeight = FontWeight.Bold),
                    color = roleColor,
                )
            }
        }
    }
}

@Composable
private fun DebtListRow(row: DebtRow, onOpenDebt: (Long) -> Unit) {
    val needsAttention = row.summary.state == DebtState.OVERDUE || row.summary.remaining.isNegative
    val formatDate = rememberDateFormatter()
    Column(modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(WalletTheme.dimensions.relatedGap)) {
        WalletListRow(
            headline = debtRowHeadline(row.summary.debt.counterparty),
            supporting = stringResource(R.string.debts_remaining_w2),
            leadingIcon = if (needsAttention) WalletIcons.Warning else WalletIcons.OwedBefore,
            trailing = { MoneyText(row.remainingDisplay, style = MaterialTheme.typography.bodyMedium) },
            onClick = { onOpenDebt(row.summary.debt.id) },
        )
        DebtStateLabel(row.summary.state)
        row.summary.debt.dueDate?.let { dueDate ->
            Text(stringResource(R.string.debts_due_w2, formatDate(dueDate)),
                modifier = Modifier.padding(horizontal = WalletTheme.dimensions.screenPadding),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (row.summary.remaining.isNegative) {
            Text(stringResource(R.string.debts_negative), color = MaterialTheme.colorScheme.error)
        }
    }
}

/** The state of a debt as a word, with a warning icon when it is overdue. */
@Composable
internal fun DebtStateLabel(state: DebtState) {
    val label = when (state) {
        DebtState.OPEN -> R.string.debts_open
        DebtState.OVERDUE -> R.string.debts_overdue
        DebtState.SETTLED -> R.string.debts_settled
        DebtState.HIDDEN -> R.string.debts_hidden
    }
    Row(horizontalArrangement = Arrangement.spacedBy(WalletTheme.dimensions.relatedGap),
        modifier = Modifier.padding(horizontal = WalletTheme.dimensions.screenPadding)) {
        if (state == DebtState.OVERDUE) {
            Icon(WalletIcons.Warning, contentDescription = null, tint = MaterialTheme.colorScheme.error)
        }
        Text(stringResource(label), style = MaterialTheme.typography.labelMedium)
    }
}
