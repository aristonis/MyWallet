package org.aristonis.mywallet.ui.reports

// Amounts arrive pre-formatted from the view-model (per currency + locale), so this screen never
// formats money itself.

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.aristonis.mywallet.R
import org.aristonis.mywallet.domain.model.TrackingPeriod
import org.aristonis.mywallet.domain.model.TrackingWindow
import org.aristonis.mywallet.ui.CategoryLabel
import org.aristonis.mywallet.ui.components.MoneyText
import org.aristonis.mywallet.ui.components.StatusCard
import org.aristonis.mywallet.ui.components.StatusTone
import org.aristonis.mywallet.ui.components.WalletTopAppBar
import org.aristonis.mywallet.ui.format.AmountRole
import org.aristonis.mywallet.ui.theme.MyWalletTheme
import org.aristonis.mywallet.ui.theme.amountColor
import org.aristonis.mywallet.ui.window.DateRangeAction
import org.aristonis.mywallet.ui.window.DateWindowActions
import org.aristonis.mywallet.ui.window.DateWindowBar
import java.time.LocalDate

private val SCREEN_PADDING = 16.dp
private val REGION_SPACING = 16.dp
private val LINE_SPACING = 8.dp

/** Tracking: income / expense / net and both by-category breakdowns over a chosen span. Read-only. */
@Composable
fun ReportsScreen(viewModel: ReportsViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    ReportsContent(
        state = state,
        windowActions = DateWindowActions(
            onSelectPeriod = viewModel::selectPeriod,
            onStep = viewModel::step,
            onJumpTo = viewModel::jumpTo,
            onSelectRange = viewModel::selectRange,
            onClearRange = viewModel::clearRange,
        ),
    )
}

/**
 * The date bar stays fixed above a bounded list, so stepping to another month never means scrolling
 * back up to find the arrows.
 *
 * Which categories are open lives here rather than in the list: a step to a month with a missing
 * rate swaps the list for a warning, and stepping back should find the same categories still open.
 */
@Composable
internal fun ReportsContent(state: ReportsUiState, windowActions: DateWindowActions) {
    val expanded = rememberSaveable(stateSaver = ExpandedIdsSaver) { mutableStateOf(emptySet<Long>()) }
    val onToggle: (Long) -> Unit = remember(expanded) {
        { id -> expanded.value = if (id in expanded.value) expanded.value - id else expanded.value + id }
    }
    Scaffold(
        topBar = {
            WalletTopAppBar(
                title = stringResource(R.string.tracking_title),
                actions = { DateRangeAction(window = state.window, onSelectRange = windowActions.onSelectRange) },
            )
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .padding(innerPadding)
                .padding(SCREEN_PADDING)
                .fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(REGION_SPACING),
        ) {
            DateWindowBar(window = state.window, actions = windowActions)

            when (val data = state.data) {
                ReportsData.Loading -> CircularProgressIndicator()

                is ReportsData.MissingRate -> StatusCard(
                    tone = StatusTone.WARNING,
                    message = stringResource(R.string.reports_missing_rate, data.currencyCode),
                    iconDescription = stringResource(R.string.cd_warning),
                )

                is ReportsData.Ready -> ReportList(
                    data = data,
                    expandedIds = expanded.value,
                    onToggle = onToggle,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

/**
 * The summary and both breakdowns in one scrolling list. It is bounded by [modifier]: without a
 * bound the list asks for the height of all its rows, pushing the last of them past the bottom of
 * the screen where no scroll can reach them.
 */
@Composable
private fun ReportList(
    data: ReportsData.Ready,
    expandedIds: Set<Long>,
    onToggle: (Long) -> Unit,
    modifier: Modifier,
) {
    LazyColumn(modifier = modifier) {
        item(key = "summary") { SummaryCard(data) }
        categorySection(
            section = CategorySection.INCOME,
            title = R.string.reports_income_by_category,
            emptyText = R.string.reports_no_income,
            rows = data.incomeCategories,
            expandedIds = expandedIds,
            onToggle = onToggle,
        )
        categorySection(
            section = CategorySection.EXPENSE,
            title = R.string.reports_spending_by_category,
            emptyText = R.string.reports_no_spending,
            rows = data.expenseCategories,
            expandedIds = expandedIds,
            onToggle = onToggle,
        )
    }
}

/**
 * Income, expense and net in one restrained block. Net sits below a divider because it is the
 * conclusion of the two lines above it, not a third item of the same kind.
 */
@Composable
private fun SummaryCard(data: ReportsData.Ready) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(SCREEN_PADDING),
            verticalArrangement = Arrangement.spacedBy(LINE_SPACING),
        ) {
            SummaryLine(stringResource(R.string.reports_income), data.incomeDisplay, amountColor(AmountRole.INCOME))
            SummaryLine(stringResource(R.string.reports_expenses), data.expenseDisplay, amountColor(AmountRole.EXPENSE))
            HorizontalDivider()
            SummaryLine(
                label = stringResource(R.string.reports_net),
                amount = data.netDisplay,
                amountColor = MaterialTheme.colorScheme.onSurface,
                labelStyle = MaterialTheme.typography.titleMedium,
            )
        }
    }
}

@Composable
private fun SummaryLine(
    label: String,
    amount: String,
    amountColor: Color,
    labelStyle: TextStyle = MaterialTheme.typography.bodyLarge,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = labelStyle, modifier = Modifier.weight(1f))
        MoneyText(amount, style = MaterialTheme.typography.titleMedium, color = amountColor)
    }
}

@Preview(showBackground = true)
@Composable
private fun ReportsPreview() {
    MyWalletTheme(dynamicColor = false) {
        ReportsContent(
            state = ReportsUiState(
                window = TrackingWindow.Period(TrackingPeriod.MONTH, LocalDate.of(2026, 8, 10)),
                data = ReportsData.Ready(
                    incomeDisplay = "2,000.00 USD",
                    expenseDisplay = "1,275.50 USD",
                    netDisplay = "724.50 USD",
                    incomeCategories = listOf(
                        CategoryRow(10, CategoryLabel.Named("Salary"), "2,000.00 USD", subCategories = emptyList()),
                    ),
                    expenseCategories = listOf(
                        CategoryRow(
                            1, CategoryLabel.Named("Food"), "620.00 USD",
                            subCategories = listOf(
                                SubCategoryRow(11, CategoryLabel.Named("Groceries"), "500.00 USD"),
                                SubCategoryRow(null, CategoryLabel.NoSubCategory, "120.00 USD"),
                            ),
                        ),
                        CategoryRow(2, CategoryLabel.Named("Transport"), "410.50 USD", subCategories = emptyList()),
                        CategoryRow(3, CategoryLabel.Named("Other"), "245.00 USD", subCategories = emptyList()),
                    ),
                ),
            ),
            windowActions = DateWindowActions(),
        )
    }
}
