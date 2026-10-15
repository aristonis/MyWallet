package org.aristonis.mywallet.ui.debt

import androidx.activity.compose.BackHandler
import androidx.annotation.StringRes
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import java.time.LocalDate
import org.aristonis.mywallet.R
import org.aristonis.mywallet.domain.model.DebtSide
import org.aristonis.mywallet.ui.components.LabeledDropdown
import org.aristonis.mywallet.ui.components.WalletTopAppBar
import org.aristonis.mywallet.ui.format.bidiIsolate
import org.aristonis.mywallet.ui.message.text
import org.aristonis.mywallet.ui.theme.WalletTheme
import org.aristonis.mywallet.ui.transaction.DateField
import org.aristonis.mywallet.ui.transaction.RateSection
import org.aristonis.mywallet.ui.transaction.accountLabel

/**
 * Form that records a repayment, or edits one when [repaymentId] is set. The domain resolves the
 * remaining balance inside its transaction; the screen only shows what it was given. In edit mode
 * [onOpenEntry] leads to the entry editor, where the repayment can be deleted.
 */
@Composable
fun RecordRepaymentScreen(
    debtId: Long,
    repaymentId: Long? = null,
    onDone: () -> Unit,
    onOpenEntry: (Long) -> Unit,
    viewModel: RecordRepaymentViewModel = hiltViewModel(),
) {
    LaunchedEffect(debtId, repaymentId) { viewModel.load(debtId, repaymentId) }
    val state by viewModel.state.collectAsStateWithLifecycle()
    LaunchedEffect(state.saved) { if (state.saved) onDone() }
    BackHandler(onBack = onDone)
    RecordRepaymentContent(
        state = state,
        onDone = onDone,
        onOpenEntry = onOpenEntry,
        onAccountSelected = viewModel::selectAccount,
        onPrincipalChanged = viewModel::setPrincipal,
        onAccountPrincipalChanged = viewModel::setAccountPrincipal,
        onInterestChanged = viewModel::setInterest,
        onInterestCategorySelected = viewModel::selectInterestCategory,
        onPayRest = { viewModel.payTheRest(true) },
        onRateChanged = viewModel::setRate,
        onDateSelected = viewModel::setDate,
        onNoteChanged = viewModel::setNote,
        onSubmit = viewModel::submit,
    )
}

@Composable
internal fun RecordRepaymentContent(
    state: RecordRepaymentUiState,
    onDone: () -> Unit,
    onOpenEntry: (Long) -> Unit,
    onAccountSelected: (Long) -> Unit,
    onPrincipalChanged: (String) -> Unit,
    onAccountPrincipalChanged: (String) -> Unit,
    onInterestChanged: (String) -> Unit,
    onInterestCategorySelected: (Long) -> Unit,
    onPayRest: () -> Unit,
    onRateChanged: (String, String) -> Unit,
    onDateSelected: (LocalDate) -> Unit,
    onNoteChanged: (String) -> Unit,
    onSubmit: () -> Unit,
) {
    val debtCurrency = state.debtCurrency
    val account = state.selectedAccount
    val accountCode = bidiIsolate(account?.currencyCode.orEmpty())
    val isEditing = state.editingRepaymentId != null
    val title = if (isEditing) R.string.repayment_title_edit else R.string.repayment_title
    Scaffold(topBar = { WalletTopAppBar(stringResource(title), onBack = onDone) }) { padding ->
        Column(
            modifier = Modifier.padding(padding).padding(WalletTheme.dimensions.screenPadding)
                .fillMaxSize().verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(WalletTheme.dimensions.regionGap),
        ) {
            if (state.isLoading) CircularProgressIndicator()
            if (debtCurrency != null) {
                RepaymentIdentity(state)
                LabeledDropdown(
                    label = stringResource(R.string.field_from_account),
                    selectedText = account?.let { selected -> accountLabel(selected) }
                        ?: stringResource(R.string.value_select_prompt),
                    items = state.accounts,
                    itemLabel = ::accountLabel,
                    onSelect = { onAccountSelected(it.id) },
                )
                OutlinedTextField(
                    value = state.principalInput,
                    onValueChange = onPrincipalChanged,
                    label = { Text(stringResource(R.string.repayment_principal, bidiIsolate(debtCurrency))) },
                    enabled = !state.payRest,
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.fillMaxWidth(),
                )
                TextButton(onClick = onPayRest, enabled = !state.isSubmitting) {
                    Text(stringResource(R.string.repayment_pay_rest))
                }
                if (account != null && account.currencyCode != debtCurrency) {
                    OutlinedTextField(
                        value = state.accountPrincipalInput,
                        onValueChange = onAccountPrincipalChanged,
                        label = { Text(stringResource(R.string.repayment_account_principal, accountCode)) },
                        enabled = !state.payRest,
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                if (account != null) {
                    OutlinedTextField(
                        value = state.interestInput,
                        onValueChange = onInterestChanged,
                        label = { Text(stringResource(R.string.repayment_interest, accountCode)) },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                if (state.interestInput.isNotBlank() && state.interestCategories.isNotEmpty()) {
                    val selected = state.interestCategories.firstOrNull { it.id == state.interestCategoryId }
                    LabeledDropdown(
                        label = stringResource(R.string.repayment_interest_category),
                        selectedText = selected?.name ?: stringResource(R.string.value_none),
                        items = state.interestCategories,
                        itemLabel = { it.name },
                        onSelect = { onInterestCategorySelected(it.id) },
                    )
                }
                RateSection(
                    state.fx?.baseCurrencyCode, state.rateFields, onRateChanged, rateRequired = state.rateRequired,
                )
                state.pairRate?.let { pair ->
                    Text(
                        bidiIsolate(stringResource(R.string.add_transaction_pair_rate, pair.from, pair.rate, pair.to)),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                DateField(state.date, onDateSelected)
                OutlinedTextField(
                    value = state.note,
                    onValueChange = onNoteChanged,
                    label = { Text(stringResource(R.string.field_note)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                RepaymentPreview(state)
            }
            // A disabled Save needs a reason the owner can act on, not silence.
            (state.error ?: state.blockedReason)?.let {
                Text(
                    it.text(),
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                )
            }
            state.editingRepaymentId?.let { id ->
                OutlinedButton(onClick = { onOpenEntry(id) }, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.repayment_open_entry))
                }
            }
            Button(onClick = onSubmit, enabled = state.canSubmit, modifier = Modifier.fillMaxWidth()) {
                if (state.isSubmitting) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                else Text(stringResource(if (isEditing) R.string.repayment_save_edit else R.string.repayment_save))
            }
        }
    }
}

@Composable
private fun RepaymentIdentity(state: RecordRepaymentUiState) {
    val debt = state.debt?.summary?.debt ?: return
    val side = if (debt.side == DebtSide.I_OWE) R.string.debts_i_owe else R.string.debts_owed_to_me
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer), border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)) {
        Column(Modifier.fillMaxWidth().padding(WalletTheme.dimensions.cardPadding),
            verticalArrangement = Arrangement.spacedBy(WalletTheme.dimensions.relatedGap)) {
            Text(stringResource(R.string.repayment_context_w2, bidiIsolate(debt.counterparty), stringResource(side)),
                style = MaterialTheme.typography.titleMedium)
            AmountLine(R.string.repayment_remaining, state.remainingDisplay)
        }
    }
}

/** Only supplied quote output is shown; the preview does not predict a post-save balance. */
@Composable
private fun RepaymentPreview(state: RecordRepaymentUiState) {
    if (state.accountMovesDisplay == null) return
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
        Column(Modifier.fillMaxWidth().padding(WalletTheme.dimensions.cardPadding),
            verticalArrangement = Arrangement.spacedBy(WalletTheme.dimensions.relatedGap)) {
            Text(stringResource(R.string.repayment_preview_w2), style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onPrimaryContainer)
            AmountLine(R.string.repayment_account_moves, state.accountMovesDisplay,
                MaterialTheme.colorScheme.onPrimaryContainer)
        }
    }
}

/** One computed amount the domain will use, shown so a saved figure is never a surprise. */
@Composable
private fun AmountLine(@StringRes format: Int, amount: String?,
    color: Color = MaterialTheme.colorScheme.onSurfaceVariant) {
    if (amount == null) return
    Text(
        stringResource(format, bidiIsolate(amount)),
        color = color,
    )
}
