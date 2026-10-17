package org.aristonis.mywallet.ui.transaction

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Badge
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import org.aristonis.mywallet.R
import org.aristonis.mywallet.domain.model.TransactionFilter
import org.aristonis.mywallet.domain.model.TypeGroup

/** The independently selected types scroll independently from the sheet button. */
@Composable
internal fun TransactionFilterBar(
    filter: TransactionFilter,
    onType: (TypeGroup) -> Unit,
    onOpenSheet: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        LazyRow(Modifier.weight(1f).testTag("transaction-type-filters"), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(TypeGroup.entries) { type ->
                FilterChip(selected = if (type == TypeGroup.ALL) filter.types.isEmpty() else type in filter.types, onClick = { onType(type) }, label = { Text(type.filterLabel()) })
            }
        }
        TextButton(onClick = onOpenSheet) {
            Text(stringResource(R.string.transaction_filter_button))
            if (filter.activeSheetCount > 0) {
                Badge(Modifier.padding(start = 4.dp)) { Text(filter.activeSheetCount.toString()) }
            }
        }
    }
}

@Composable
internal fun TypeGroup.filterLabel(): String = stringResource(when (this) {
    TypeGroup.ALL -> R.string.transaction_filter_all
    TypeGroup.INCOME -> R.string.transaction_filter_income
    TypeGroup.EXPENSE -> R.string.transaction_filter_expense
    TypeGroup.TRANSFER -> R.string.transaction_filter_transfer
    TypeGroup.DEBTS -> R.string.transaction_filter_debts
})
