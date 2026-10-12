package org.aristonis.mywallet.ui.transaction

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import org.aristonis.mywallet.R
import org.aristonis.mywallet.data.format.MoneyFormatter
import org.aristonis.mywallet.domain.model.Currency
import org.aristonis.mywallet.ui.format.bidiIsolate
import org.aristonis.mywallet.ui.format.display

/** Displays the gross, total costs, account receipt and new debt without conflating their timing. */
@Composable
internal fun DeductionSummaryCard(
    summary: IncomeDeductionSummary,
    currencies: List<Currency>,
    modifier: Modifier = Modifier,
) {
    val locale = LocalConfiguration.current.locales[0]
    val formatter = remember(locale) { MoneyFormatter(locale) }
    val values = listOf(
        R.string.income_summary_gross to summary.gross,
        R.string.income_summary_deductions to summary.deductions,
        R.string.income_summary_receives to summary.accountReceives,
        R.string.income_summary_owed to summary.owed,
    )
    Card(modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            values.forEach { (label, amount) ->
                Column {
                    Text(stringResource(label), style = MaterialTheme.typography.labelMedium)
                    Text(bidiIsolate(formatter.display(amount, currencies)),
                        style = MaterialTheme.typography.titleMedium)
                }
            }
        }
    }
}
