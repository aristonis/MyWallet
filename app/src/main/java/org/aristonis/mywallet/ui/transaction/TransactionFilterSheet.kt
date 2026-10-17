package org.aristonis.mywallet.ui.transaction

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import org.aristonis.mywallet.R
import org.aristonis.mywallet.domain.model.Account
import org.aristonis.mywallet.domain.model.Category
import org.aristonis.mywallet.domain.model.TransactionFilter
import org.aristonis.mywallet.ui.label
import org.aristonis.mywallet.ui.text
import org.aristonis.mywallet.ui.theme.WalletTheme

/** Optional account and category restrictions; child categories follow their parent. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun TransactionFilterSheet(
    filter: TransactionFilter,
    accounts: List<Account>,
    categories: List<Category>,
    onAccount: (Long) -> Unit,
    onClearAccounts: () -> Unit,
    onCategory: (Long) -> Unit,
    onClearCategories: () -> Unit,
    onClear: () -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(WalletTheme.dimensions.screenPadding),
            verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.transaction_filter_button), style = MaterialTheme.typography.titleLarge)
            TextButton(onClick = onClear) { Text(stringResource(R.string.transaction_filter_clear)) }
            Text(stringResource(R.string.transaction_filter_account), style = MaterialTheme.typography.titleMedium)
            FilterOption(stringResource(R.string.transaction_filter_all), filter.accountIds.isEmpty(), onSelect = onClearAccounts)
            accounts.sortedWith(compareBy<Account> { it.sortOrder }.thenBy { it.id }).forEach { account ->
                FilterOption(account.name, account.id in filter.accountIds) { onAccount(account.id) }
            }
            Text(stringResource(R.string.transaction_filter_category), style = MaterialTheme.typography.titleMedium)
            FilterOption(stringResource(R.string.transaction_filter_all), filter.categoryIds.isEmpty(), onSelect = onClearCategories)
            categories.filter { it.parentId == null }.sortedBy { it.id }.forEach { parent ->
                FilterOption(parent.label().text(), parent.id in filter.categoryIds) { onCategory(parent.id) }
                categories.filter { it.parentId == parent.id }.sortedBy { it.id }.forEach { child ->
                    FilterOption(stringResource(R.string.transaction_filter_category_child, parent.label().text(), child.label().text()),
                        child.id in filter.categoryIds, isChild = true) { onCategory(child.id) }
                }
            }
        }
    }
}

@Composable
private fun FilterOption(label: String, selected: Boolean, isChild: Boolean = false, onSelect: () -> Unit) {
    Row(Modifier.fillMaxWidth().selectable(selected, role = Role.Checkbox, onClick = onSelect)
        .padding(start = if (isChild) 24.dp else 0.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Checkbox(selected, onCheckedChange = null)
        Text(label, Modifier.padding(start = 8.dp))
    }
}
