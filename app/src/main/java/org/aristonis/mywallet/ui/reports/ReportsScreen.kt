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
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import org.aristonis.mywallet.ui.icons.WalletIcons
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.aristonis.mywallet.R
import org.aristonis.mywallet.ui.CategoryLabel
import org.aristonis.mywallet.ui.text
import org.aristonis.mywallet.domain.model.TrackingPeriod
import org.aristonis.mywallet.ui.components.EmptyState
import org.aristonis.mywallet.ui.components.MoneyText
import org.aristonis.mywallet.ui.components.SectionHeader
import org.aristonis.mywallet.ui.components.StatusCard
import org.aristonis.mywallet.ui.components.StatusTone
import org.aristonis.mywallet.ui.components.WalletTopAppBar
import org.aristonis.mywallet.ui.format.AmountRole
import org.aristonis.mywallet.ui.theme.amountColor
import org.aristonis.mywallet.ui.theme.MyWalletTheme

/**
 * Reports: income / expense / net and spending-by-category over a selectable period. Read-only.
 * [onDone] returns to Home (Done button and system back) — no nav library, the parent toggles it.
 */
@Composable
fun ReportsScreen(viewModel: ReportsViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    ReportsContent(state = state, onSelectPeriod = viewModel::selectPeriod)
}

@Composable
internal fun ReportsContent(
    state: ReportsUiState,
    onSelectPeriod: (TrackingPeriod) -> Unit,
) {
    Scaffold(
        topBar = { WalletTopAppBar(title = stringResource(R.string.tracking_title)) },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .padding(innerPadding)
                .padding(16.dp)
                .fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            PeriodSelector(selected = state.selectedPeriod, onSelectPeriod = onSelectPeriod)

            when (val data = state.data) {
                ReportsData.Loading -> CircularProgressIndicator()

                is ReportsData.MissingRate -> StatusCard(
                    tone = StatusTone.WARNING,
                    message = stringResource(R.string.reports_missing_rate, data.currencyCode),
                    iconDescription = stringResource(R.string.cd_warning),
                )

                is ReportsData.Ready -> {
                    SummaryCard(data)
                    SectionHeader(title = stringResource(R.string.reports_spending_by_category))
                    if (data.categories.isEmpty()) {
                        EmptyState(message = stringResource(R.string.reports_no_spending))
                    } else {
                        LazyColumn(
                    // Without a bound the list asks for the height of all its rows, pushing the
                    // last of them past the bottom of the screen where no scroll can reach them.
                            modifier = Modifier.weight(1f),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            items(data.categories, key = { it.id }) { row -> CategoryRowCard(row) }
                        }
                    }
                }
            }
        }
    }
}

/**
 * Which period the report covers.
 *
 * A segmented row is the right control for this — exactly one period is always in effect, and the row
 * says "pick one of these" rather than "toggle any of these". But its five segments share the width
 * equally, so each label gets a fifth of the screen: at large text sizes "All time" and "Month" have
 * nowhere to go but truncation, and a control whose options cannot be read is not a control.
 *
 * Past that point the same single choice moves into a menu, where a label gets the full width. The
 * choice, the options and the selection semantics are identical; only the shape changes.
 */
@Composable
private fun PeriodSelector(selected: TrackingPeriod, onSelectPeriod: (TrackingPeriod) -> Unit) {
    if (LocalDensity.current.fontScale >= LARGE_TEXT_SCALE) {
        PeriodMenu(current = selected, onSelectPeriod = onSelectPeriod)
    } else {
        PeriodSegments(selected = selected, onSelectPeriod = onSelectPeriod)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PeriodSegments(selected: TrackingPeriod, onSelectPeriod: (TrackingPeriod) -> Unit) {
    SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
        TrackingPeriod.entries.forEachIndexed { index, period ->
            SegmentedButton(
                selected = period == selected,
                onClick = { onSelectPeriod(period) },
                shape = SegmentedButtonDefaults.itemShape(index = index, count = TrackingPeriod.entries.size),
            ) {
                Text(periodLabel(period), maxLines = 1)
            }
        }
    }
}

/**
 * The same five periods at a text size the row cannot hold. Each entry carries radio-button
 * semantics inside a selectable group, so a screen reader still announces this as one choice out of
 * five with one in effect — which is what the segmented row was saying visually.
 */
@Composable
private fun PeriodMenu(current: TrackingPeriod, onSelectPeriod: (TrackingPeriod) -> Unit) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    // The button shows only the period, which on its own says nothing about what it controls.
    val label = stringResource(R.string.cd_period_selector, periodLabel(current))

    Box(modifier = Modifier.fillMaxWidth()) {
        OutlinedButton(
            onClick = { expanded = true },
            modifier = Modifier
                .fillMaxWidth()
                .semantics { contentDescription = label },
        ) {
            Text(periodLabel(current), modifier = Modifier.weight(1f))
            Icon(imageVector = WalletIcons.Expand, contentDescription = null)
        }
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            modifier = Modifier.selectableGroup(),
        ) {
            TrackingPeriod.entries.forEach { period ->
                val isCurrent = period == current
                DropdownMenuItem(
                    text = { Text(periodLabel(period)) },
                    trailingIcon = {
                        if (isCurrent) {
                            Icon(imageVector = WalletIcons.Selected, contentDescription = null)
                        }
                    },
                    onClick = {
                        expanded = false
                        onSelectPeriod(period)
                    },
                    modifier = Modifier.semantics {
                        role = Role.RadioButton
                        selected = isCurrent
                    },
                )
            }
        }
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
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
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
    amountColor: androidx.compose.ui.graphics.Color,
    labelStyle: androidx.compose.ui.text.TextStyle = MaterialTheme.typography.bodyLarge,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = labelStyle, modifier = Modifier.weight(1f))
        MoneyText(amount, style = MaterialTheme.typography.titleMedium, color = amountColor)
    }
}

@Composable
private fun CategoryRowCard(row: CategoryRow) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(row.label.text(), style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            MoneyText(row.totalDisplay, style = MaterialTheme.typography.titleMedium)
        }
    }
}

/**
 * Where the segmented row stops being usable. Below this the five labels still share the width
 * legibly; at and above it the longest of them no longer fits its fifth of a compact screen.
 */
private const val LARGE_TEXT_SCALE = 1.3f

@Composable
private fun periodLabel(period: TrackingPeriod): String = stringResource(
    when (period) {
        TrackingPeriod.DAY -> R.string.period_day
        TrackingPeriod.WEEK -> R.string.period_week
        TrackingPeriod.MONTH -> R.string.period_month
        TrackingPeriod.YEAR -> R.string.period_year
        TrackingPeriod.ALL_TIME -> R.string.period_all_time
    },
)

@Preview(showBackground = true)
@Composable
private fun ReportsPreview() {
    MyWalletTheme(dynamicColor = false) {
        ReportsContent(
            state = ReportsUiState(
                selectedPeriod = TrackingPeriod.MONTH,
                data = ReportsData.Ready(
                    incomeDisplay = "2,000.00 USD",
                    expenseDisplay = "1,275.50 USD",
                    netDisplay = "724.50 USD",
                    categories = listOf(
                        CategoryRow(1, CategoryLabel.Named("Food"), "620.00 USD"),
                        CategoryRow(2, CategoryLabel.Named("Transport"), "410.50 USD"),
                        CategoryRow(3, CategoryLabel.Named("Other"), "245.00 USD"),
                    ),
                ),
            ),
            onSelectPeriod = {},
        )
    }
}
