package org.aristonis.mywallet.ui.transaction

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import org.aristonis.mywallet.R

/** The three kinds always on the form; the rest sit behind "More". */
internal val standardTransactionTypes =
    listOf(TransactionType.INCOME, TransactionType.EXPENSE, TransactionType.TRANSFER)
/** The kinds offered in the "More" sheet. */
internal val moreTransactionTypes = listOf(TransactionType.REFUND, TransactionType.BORROW, TransactionType.LEND,
    TransactionType.OWED_BEFORE, TransactionType.CREDIT_PURCHASE, TransactionType.EARNED_UNPAID)

/** The sheet that lists the kinds behind "More"; choosing one selects it and closes the sheet. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun MoreKindsSheet(onTypeSelected: (TransactionType) -> Unit, onDismiss: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.kind_more_title), style = MaterialTheme.typography.titleLarge)
            moreTransactionTypes.forEach { type ->
                TextButton(onClick = { onTypeSelected(type); onDismiss() },
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                    Text(typeLabel(type), modifier = Modifier.fillMaxWidth())
                }
            }
        }
    }
}
