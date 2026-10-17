package org.aristonis.mywallet.ui.deduction

import android.content.res.Configuration
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
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
import androidx.compose.ui.tooling.preview.Preview
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.aristonis.mywallet.R
import org.aristonis.mywallet.domain.model.DeductionItem
import org.aristonis.mywallet.domain.model.DeductionMode
import org.aristonis.mywallet.domain.model.DeductionTiming
import org.aristonis.mywallet.ui.components.AristonisLoader
import org.aristonis.mywallet.ui.components.LoadFailedState
import org.aristonis.mywallet.ui.components.EmptyState
import org.aristonis.mywallet.ui.components.MenuAction
import org.aristonis.mywallet.ui.components.OverflowMenu
import org.aristonis.mywallet.ui.components.SectionHeader
import org.aristonis.mywallet.ui.components.StatusCard
import org.aristonis.mywallet.ui.components.StatusTone
import org.aristonis.mywallet.ui.components.WalletListRow
import org.aristonis.mywallet.ui.components.WalletTopAppBar
import org.aristonis.mywallet.ui.format.bidiIsolate
import org.aristonis.mywallet.ui.message.text
import org.aristonis.mywallet.ui.theme.WalletTheme
import org.aristonis.mywallet.ui.icons.WalletIcons
import org.aristonis.mywallet.ui.theme.MyWalletTheme
import java.math.BigDecimal

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
        DeductionsList(state, onAdd, onEdit, { deletingId = it }, viewModel::setEnabled,
            viewModel::retry, Modifier.padding(padding))
    }
    val deleting = state.items.find { it.item.id == deletingId }?.item
    deleting?.let { item ->
        DeleteDialog(item, onDismiss = { deletingId = null }, onConfirm = {
            deletingId = null
            viewModel.delete(item.id)
        })
    }
    state.items.find { it.item.id == state.disableOfferedId }?.item?.let {
        DisableDialog(it.name, state.isWorking, viewModel)
    }
}

@Composable
private fun DeductionsList(
    state: DeductionsUiState,
    onAdd: () -> Unit,
    onEdit: (Long) -> Unit,
    onDelete: (Long) -> Unit,
    onEnabled: (Long, Boolean) -> Unit,
    onRetry: () -> Unit,
    modifier: Modifier,
) {
    if (state.isLoading) {
        AristonisLoader(modifier.fillMaxSize())
        return
    }
    val dimensions = WalletTheme.dimensions
    LazyColumn(modifier.fillMaxSize().padding(horizontal = dimensions.screenPadding),
        verticalArrangement = Arrangement.spacedBy(dimensions.relatedGap)) {
        item { ReadStatus(state, onRetry, onAdd) }
        if (!state.loadFailed && !state.isLoading) {
            if (state.items.isNotEmpty()) item {
                SectionHeader(stringResource(R.string.deductions_title), actions = {
                    TextButton(onClick = onAdd, enabled = !state.isWorking,
                        modifier = Modifier.heightIn(min = dimensions.minTouchTarget)) {
                        Text(stringResource(R.string.action_add))
                    }
                })
            }
            items(state.items, key = { it.item.id }) { row ->
                DeductionRow(row, state.isWorking, onEdit, onDelete, onEnabled)
            }
        }
        state.error?.let { error -> item { StatusCard(StatusTone.ERROR, error.text()) } }
    }
}

@Composable
private fun ReadStatus(state: DeductionsUiState, onRetry: () -> Unit, onAdd: () -> Unit) {
    when {
        state.loadFailed -> LoadFailedState(stringResource(R.string.deductions_load_failed), onRetry)
        state.items.isEmpty() -> EmptyState(stringResource(R.string.deductions_empty),
            actionLabel = stringResource(R.string.deduction_add), onAction = onAdd)
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
    val status = stringResource(if (item.enabled) R.string.management_deduction_enabled
        else R.string.management_deduction_disabled)
    Column {
        WalletListRow(
            headline = bidiIsolate(item.name),
            supporting = stringResource(R.string.management_deduction_row_summary, summary(row), status),
            modifier = Modifier.background(MaterialTheme.colorScheme.surfaceContainer),
            onClick = if (isWorking) null else ({ onEdit(item.id) }),
            trailing = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Switch(checked = item.enabled, onCheckedChange = { onEnabled(item.id, it) },
                        enabled = !isWorking,
                        modifier = Modifier.semantics { contentDescription = switchLabel })
                    if (!isWorking) {
                        OverflowMenu(contentDescription = stringResource(R.string.cd_row_actions, bidiIsolate(item.name)),
                            actions = listOf(
                                MenuAction(stringResource(R.string.action_edit), { onEdit(item.id) }, WalletIcons.Edit),
                                MenuAction(stringResource(R.string.action_delete), { onDelete(item.id) },
                                    WalletIcons.Delete, isDestructive = true),
                            ))
                    }
                }
            },
        )
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
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
private fun DisableDialog(name: String, isWorking: Boolean, viewModel: DeductionsViewModel) {
    AlertDialog(onDismissRequest = viewModel::dismissDisableOffer,
        title = { Text(stringResource(R.string.management_deduction_disable_title, bidiIsolate(name))) },
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

@Preview(name = "Deductions light", showBackground = true, widthDp = 360)
@Preview(name = "Deductions dark", showBackground = true, widthDp = 360,
    uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun DeductionsPreview() {
    MyWalletTheme(dynamicColor = false) {
        DeductionsList(
            state = DeductionsUiState(items = listOf(DeductionListRow(
                DeductionItem(1, "Tax", DeductionMode.PERCENT, BigDecimal.TEN,
                    timing = DeductionTiming.NOW, expenseCategoryId = 1), "10")), isLoading = false),
            onAdd = {}, onEdit = {}, onDelete = {}, onEnabled = { _, _ -> }, onRetry = {},
            modifier = Modifier,
        )
    }
}
