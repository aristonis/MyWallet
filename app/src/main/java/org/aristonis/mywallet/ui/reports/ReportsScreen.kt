package org.aristonis.mywallet.ui.reports

// UI copy hardcoded; localizing strings (RTL/i18n) comes later. Amounts shown as "amount CODE";
// per-currency symbol + locale formatting comes later too.

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
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
import org.aristonis.mywallet.domain.model.TrackingPeriod
import org.aristonis.mywallet.ui.theme.MyWalletTheme

/**
 * Reports: income / expense / net and spending-by-category over a selectable period. Read-only.
 * [onDone] returns to Home (Done button and system back) — no nav library, the parent toggles it.
 */
@Composable
fun ReportsScreen(
    onDone: () -> Unit,
    viewModel: ReportsViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    BackHandler(onBack = onDone)
    ReportsContent(state = state, onSelectPeriod = viewModel::selectPeriod, onDone = onDone)
}

@Composable
private fun ReportsContent(
    state: ReportsUiState,
    onSelectPeriod: (TrackingPeriod) -> Unit,
    onDone: () -> Unit,
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
                Text("Reports", style = MaterialTheme.typography.headlineMedium, modifier = Modifier.weight(1f))
                TextButton(onClick = onDone) { Text("Done") }
            }

            PeriodSelector(selected = state.selectedPeriod, onSelectPeriod = onSelectPeriod)

            when (val data = state.data) {
                ReportsData.Loading -> CircularProgressIndicator()

                is ReportsData.MissingRate -> Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
                ) {
                    Text(
                        "Set an exchange rate for ${data.currencyCode} on the Exchange rates screen to " +
                            "see this report.",
                        modifier = Modifier.padding(16.dp),
                        color = MaterialTheme.colorScheme.onErrorContainer,
                    )
                }

                is ReportsData.Ready -> {
                    SummaryCard(data)
                    Text("Spending by category", style = MaterialTheme.typography.titleMedium)
                    if (data.categories.isEmpty()) {
                        Text("No spending this period.", style = MaterialTheme.typography.bodyMedium)
                    } else {
                        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            items(data.categories, key = { it.id }) { row -> CategoryRowCard(row) }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PeriodSelector(selected: TrackingPeriod, onSelectPeriod: (TrackingPeriod) -> Unit) {
    Row(
        modifier = Modifier.horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        TrackingPeriod.entries.forEach { period ->
            FilterChip(
                selected = period == selected,
                onClick = { onSelectPeriod(period) },
                label = { Text(periodLabel(period)) },
            )
        }
    }
}

@Composable
private fun SummaryCard(data: ReportsData.Ready) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            SummaryLine("Income", data.income.display(), MaterialTheme.colorScheme.primary)
            SummaryLine("Expenses", data.expense.display(), MaterialTheme.colorScheme.onSurface)
            SummaryLine("Net", data.net.display(), MaterialTheme.colorScheme.onSurface)
        }
    }
}

@Composable
private fun SummaryLine(label: String, amount: String, amountColor: androidx.compose.ui.graphics.Color) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        Text(amount, style = MaterialTheme.typography.titleMedium, color = amountColor)
    }
}

@Composable
private fun CategoryRowCard(row: CategoryRow) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(row.name, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            Text(row.total.display(), style = MaterialTheme.typography.titleMedium)
        }
    }
}

private fun periodLabel(period: TrackingPeriod): String = when (period) {
    TrackingPeriod.DAY -> "Day"
    TrackingPeriod.WEEK -> "Week"
    TrackingPeriod.MONTH -> "Month"
    TrackingPeriod.YEAR -> "Year"
    TrackingPeriod.ALL_TIME -> "All time"
}

/** Placeholder formatting: amount + ISO code. Per-currency symbol + locale formatting comes later. */
private fun Money.display(): String = "${amount.toPlainString()} $currencyCode"

@Preview(showBackground = true)
@Composable
private fun ReportsPreview() {
    MyWalletTheme(dynamicColor = false) {
        ReportsContent(
            state = ReportsUiState(
                selectedPeriod = TrackingPeriod.MONTH,
                data = ReportsData.Ready(
                    income = Money.of("2000.00", "USD"),
                    expense = Money.of("1275.50", "USD"),
                    net = Money.of("724.50", "USD"),
                    categories = listOf(
                        CategoryRow(1, "Food", Money.of("620.00", "USD")),
                        CategoryRow(2, "Transport", Money.of("410.50", "USD")),
                        CategoryRow(3, "Other", Money.of("245.00", "USD")),
                    ),
                ),
            ),
            onSelectPeriod = {},
            onDone = {},
        )
    }
}
