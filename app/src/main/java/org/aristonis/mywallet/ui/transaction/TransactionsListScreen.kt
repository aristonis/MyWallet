package org.aristonis.mywallet.ui.transaction

// UI copy hardcoded; localizing strings (RTL/i18n) comes later. Amounts shown as "amount CODE";
// per-currency symbol + locale formatting comes later too.

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.aristonis.mywallet.domain.model.Money
import org.aristonis.mywallet.ui.theme.MyWalletTheme
import java.time.LocalDate

/**
 * The transactions history: a simple newest-first list. Tapping a row opens it in the editor via
 * [onEditTransaction]. [onDone] returns to Home via Done or system back — no nav library.
 */
@Composable
fun TransactionsListScreen(
    onDone: () -> Unit,
    onEditTransaction: (Long) -> Unit,
    viewModel: TransactionsListViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    BackHandler(onBack = onDone)
    TransactionsListContent(state = state, onDone = onDone, onEditTransaction = onEditTransaction)
}

@Composable
private fun TransactionsListContent(
    state: TransactionsUiState,
    onDone: () -> Unit,
    onEditTransaction: (Long) -> Unit,
) {
    Scaffold { innerPadding ->
        Column(
            modifier = Modifier
                .padding(innerPadding)
                .padding(24.dp)
                .fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Transactions", style = MaterialTheme.typography.headlineMedium, modifier = Modifier.weight(1f))
                TextButton(onClick = onDone) { Text("Done") }
            }

            when {
                state.isLoading -> CircularProgressIndicator()

                state.rows.isEmpty() -> Text(
                    "No transactions yet. Add one from the home screen.",
                    style = MaterialTheme.typography.bodyMedium,
                )

                else -> LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(state.rows, key = { it.id }) { row ->
                        TransactionCard(row, onClick = { onEditTransaction(row.id) })
                    }
                }
            }
        }
    }
}

@Composable
private fun TransactionCard(row: TransactionRow, onClick: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth().clickable(onClick = onClick)) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(accountLine(row), style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                Text(amountLine(row), style = MaterialTheme.typography.titleMedium, color = amountColor(row))
            }
            row.categoryName?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            Text(row.date.toString(), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            row.note?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
        }
    }
}

private fun accountLine(row: TransactionRow): String = when (row.type) {
    TransactionRowType.TRANSFER -> "${row.accountName} → ${row.destAccountName}"
    else -> row.accountName
}

private fun amountLine(row: TransactionRow): String = when (row.type) {
    TransactionRowType.INCOME -> "+${row.amount.display()}"
    TransactionRowType.EXPENSE -> "−${row.amount.display()}"
    // Transfer shows both legs (they differ for a cross-currency move).
    TransactionRowType.TRANSFER -> "−${row.amount.display()} → +${row.destAmount?.display() ?: ""}"
}

@Composable
private fun amountColor(row: TransactionRow) = when (row.type) {
    TransactionRowType.INCOME -> MaterialTheme.colorScheme.primary
    else -> MaterialTheme.colorScheme.onSurface
}

/** Placeholder formatting: amount + ISO code. Per-currency symbol + locale formatting comes later. */
private fun Money.display(): String = "${amount.toPlainString()} $currencyCode"

@Preview(showBackground = true)
@Composable
private fun TransactionsListPreview() {
    MyWalletTheme(dynamicColor = false) {
        TransactionsListContent(
            state = TransactionsUiState(
                isLoading = false,
                rows = listOf(
                    TransactionRow(
                        id = 1, date = LocalDate.of(2026, 7, 3), type = TransactionRowType.EXPENSE,
                        accountName = "Cash", destAccountName = null, categoryName = "Food",
                        amount = Money.of("12.50", "USD"), destAmount = null, note = "Lunch",
                    ),
                    TransactionRow(
                        id = 2, date = LocalDate.of(2026, 7, 2), type = TransactionRowType.TRANSFER,
                        accountName = "Cash", destAccountName = "Euro Savings", categoryName = null,
                        amount = Money.of("11", "USD"), destAmount = Money.of("10.00", "EUR"), note = null,
                    ),
                ),
            ),
            onDone = {},
            onEditTransaction = {},
        )
    }
}
