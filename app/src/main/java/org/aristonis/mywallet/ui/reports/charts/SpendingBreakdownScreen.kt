package org.aristonis.mywallet.ui.reports.charts

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.aristonis.mywallet.R
import org.aristonis.mywallet.ui.components.LoadFailedState
import org.aristonis.mywallet.ui.components.SectionHeader
import org.aristonis.mywallet.ui.components.StatusCard
import org.aristonis.mywallet.ui.components.StatusTone
import org.aristonis.mywallet.ui.components.WalletTopAppBar
import org.aristonis.mywallet.ui.format.bidiIsolate
import org.aristonis.mywallet.ui.icons.WalletIcons
import org.aristonis.mywallet.ui.reports.GroupedLine
import org.aristonis.mywallet.ui.text
import org.aristonis.mywallet.ui.theme.WalletTheme
import org.aristonis.mywallet.ui.window.DateRangeAction
import org.aristonis.mywallet.ui.window.DateWindowActions
import org.aristonis.mywallet.ui.window.DateWindowBar
import org.aristonis.mywallet.ui.window.RefreshOnStart

/**
 * Spending breakdown, a child of Tracking: the window's expense categories as bars and as an exact
 * table. Opens on the Tracking window passed in its route. [onManageRates] opens Exchange rates.
 */
@Composable
fun SpendingBreakdownScreen(
    onBack: () -> Unit,
    onManageRates: () -> Unit,
    viewModel: SpendingBreakdownViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    RefreshOnStart { viewModel.onScreenStart() }
    SpendingBreakdownContent(
        state = state,
        windowActions = viewModel.windowActions,
        onBack = onBack,
        onRetry = viewModel::retry,
        onManageRates = onManageRates,
    )
}

/**
 * The screen, stateless, as one scrolling list so nothing is pinned over the figures at large text.
 * The date bar is the first item in every state, failure included, so other dates can still be
 * chosen. No state shows a partial figure: loading, a missing rate and a failed read each replace
 * the numbers entirely.
 */
@Composable
internal fun SpendingBreakdownContent(
    state: SpendingUiState,
    windowActions: DateWindowActions,
    onBack: () -> Unit,
    onRetry: () -> Unit,
    onManageRates: () -> Unit,
) {
    val dimensions = WalletTheme.dimensions
    Scaffold(
        topBar = {
            WalletTopAppBar(
                title = stringResource(R.string.spending_title),
                onBack = onBack,
                actions = { DateRangeAction(window = state.window, onSelectRange = windowActions.onSelectRange) },
            )
        },
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier.padding(innerPadding).fillMaxSize(),
            contentPadding = PaddingValues(dimensions.screenPadding),
            verticalArrangement = Arrangement.spacedBy(dimensions.relatedGap),
        ) {
            item(key = "window") { DateWindowBar(window = state.window, actions = windowActions) }
            if (state.isUpdating) item(key = "updating") { ChartUpdating(Modifier.testTag(ChartTags.UPDATING)) }
            if (state.loadFailed) {
                item(key = "failed") {
                    LoadFailedState(
                        message = stringResource(R.string.spending_unavailable),
                        onRetry = onRetry,
                        modifier = Modifier.announced(),
                    )
                }
                return@LazyColumn
            }
            when (val data = state.data) {
                SpendingData.Loading -> item(key = "loading") { ChartLoading(stringResource(R.string.spending_loading)) }
                is SpendingData.MissingRate -> item(key = "missing-rate") {
                    ChartMissingRate(stringResource(R.string.spending_missing_rate, data.currencyCode), onManageRates)
                }
                is SpendingData.Ready -> readyItems(data)
            }
        }
    }
}

/** The total, the notes that apply, the chart and the exact table. */
private fun LazyListScope.readyItems(data: SpendingData.Ready) {
    item(key = "hero") {
        ChartHero(
            heading = stringResource(R.string.spending_heading, data.currencyCode),
            amount = data.totalDisplay,
            caption = stringResource(R.string.spending_caption),
            modifier = Modifier.testTag(ChartTags.HERO),
        )
    }
    if (data.isEmpty) {
        item(key = "empty") {
            StatusCard(tone = StatusTone.INFO, message = stringResource(R.string.spending_empty), modifier = Modifier.announced())
        }
    }
    if (data.hasRefundEffect) {
        item(key = "refunds") {
            StatusCard(
                tone = StatusTone.WARNING,
                message = stringResource(R.string.spending_refund_note),
                modifier = Modifier.announced(),
                iconDescription = stringResource(R.string.cd_warning),
            )
        }
    }
    // A bar is drawn only for a positive spend (ChartGeometry.shareLengths).
    if (data.rows.any { it.amount.signum() > 0 }) {
        item(key = "chart-header") { SectionHeader(stringResource(R.string.spending_chart_title)) }
        item(key = "chart") { SpendingChart(data) }
    }
    if (!data.isEmpty) {
        item(key = "table-header") { SectionHeader(stringResource(R.string.spending_table_title)) }
        itemsIndexed(data.rows, key = { _, row -> "row-${row.id}" }) { index, row ->
            GroupedLine(isFirst = index == 0, isLast = index == data.rows.lastIndex) { SpendingTableRow(row) }
        }
    }
}

/** The bars, built once per breakdown rather than on every recomposition. */
@Composable
private fun SpendingChart(data: SpendingData.Ready) {
    val drawn = remember(data) { data.bars.filter { it.isDrawn } }
    SpendingBarChart(drawn, chartDescription(drawn), Modifier.testTag(ChartTags.SPENDING_CHART))
}

/**
 * A table row. A refund effect says so in words and with a warning icon, and its amount keeps its
 * minus sign, so the meaning never rests on colour.
 */
@Composable
private fun SpendingTableRow(row: SpendingRow) {
    val separator = stringResource(R.string.chart_parts_separator)
    val supporting = if (row.isRefundEffect) {
        stringResource(R.string.spending_refund_row)
    } else {
        row.previewParts.map { it.text() }.takeIf { it.isNotEmpty() }?.joinToString(separator)
    }
    ExactAmountRow(
        headline = row.label.text(),
        supporting = supporting,
        amount = row.amountDisplay,
        // Decorative: the supporting text already says what the icon marks.
        icon = if (row.isRefundEffect) WalletIcons.Warning else WalletIcons.Categories,
    )
}

/** What the chart shows, for a screen reader: how many bars, and the largest with its exact amount. */
@Composable
private fun chartDescription(drawn: List<SpendingBar>): String {
    val largest = drawn.maxBy { it.length }
    return pluralStringResource(
        R.plurals.spending_chart_description,
        drawn.size,
        drawn.size,
        bidiIsolate(largest.label.text()),
        bidiIsolate(largest.amountDisplay),
    )
}
