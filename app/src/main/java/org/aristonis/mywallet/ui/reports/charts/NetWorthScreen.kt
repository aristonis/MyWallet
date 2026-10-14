package org.aristonis.mywallet.ui.reports.charts

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.aristonis.mywallet.R
import org.aristonis.mywallet.domain.model.NoTrendReason
import org.aristonis.mywallet.ui.components.LoadFailedState
import org.aristonis.mywallet.ui.components.SectionHeader
import org.aristonis.mywallet.ui.components.StatusCard
import org.aristonis.mywallet.ui.components.StatusTone
import org.aristonis.mywallet.ui.components.WalletTopAppBar
import org.aristonis.mywallet.ui.format.bidiIsolate
import org.aristonis.mywallet.ui.format.rememberDayFormatter
import org.aristonis.mywallet.ui.reports.GroupedLine
import org.aristonis.mywallet.ui.theme.WalletTheme
import org.aristonis.mywallet.ui.window.DateRangeAction
import org.aristonis.mywallet.ui.window.DateWindowActions
import org.aristonis.mywallet.ui.window.DateWindowBar
import org.aristonis.mywallet.ui.window.RefreshOnStart

/**
 * Net worth, a child of Tracking: the current ledger restated at current rates for each day (or month
 * end) of the window, as a line and an exact table. Opens on the Tracking window passed in its route.
 * [onManageRates] opens Exchange rates.
 */
@Composable
fun NetWorthScreen(
    onBack: () -> Unit,
    onManageRates: () -> Unit,
    viewModel: NetWorthViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    RefreshOnStart { viewModel.onScreenStart() }
    NetWorthContent(
        state = state,
        windowActions = viewModel.windowActions,
        onBack = onBack,
        onRetry = viewModel::retry,
        onManageRates = onManageRates,
    )
}

/**
 * The screen, stateless, as one scrolling list so nothing is pinned over the figures at large text.
 * The date bar and the restatement label are its first items in every state, loading and failure
 * included: no figure on this screen may be read as a balance someone saw on that day. A line is
 * drawn only for a trend; every other state shows its reason and no line.
 */
@Composable
internal fun NetWorthContent(
    state: NetWorthUiState,
    windowActions: DateWindowActions,
    onBack: () -> Unit,
    onRetry: () -> Unit,
    onManageRates: () -> Unit,
) {
    val dimensions = WalletTheme.dimensions
    Scaffold(
        topBar = {
            WalletTopAppBar(
                title = stringResource(R.string.net_worth_title),
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
            item(key = "restatement") { RestatementLabel() }
            if (state.isUpdating) item(key = "updating") { ChartUpdating(Modifier.testTag(ChartTags.UPDATING)) }
            if (state.loadFailed) {
                item(key = "failed") {
                    LoadFailedState(
                        message = stringResource(R.string.net_worth_unavailable),
                        onRetry = onRetry,
                        modifier = Modifier.announced(),
                    )
                }
                return@LazyColumn
            }
            when (val data = state.data) {
                NetWorthData.Loading -> item(key = "loading") { ChartLoading(stringResource(R.string.net_worth_loading)) }
                is NetWorthData.MissingRate -> item(key = "missing-rate") {
                    ChartMissingRate(stringResource(R.string.net_worth_missing_rate, data.currencyCode), onManageRates)
                }
                NetWorthData.TooManyLegs -> item(key = "too-many") {
                    StatusCard(
                        tone = StatusTone.WARNING,
                        message = stringResource(R.string.net_worth_too_many_records),
                        modifier = Modifier.announced(),
                        iconDescription = stringResource(R.string.cd_warning),
                    )
                }
                is NetWorthData.Trend -> trendItems(data)
                is NetWorthData.CurrentOnly -> currentOnlyItems(data)
            }
        }
    }
}

/** What the figures are, and what they do not claim. */
@Composable
private fun RestatementLabel() {
    Column(verticalArrangement = Arrangement.spacedBy(WalletTheme.dimensions.grid)) {
        Text(
            text = stringResource(R.string.net_worth_restatement_label),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.testTag(ChartTags.RESTATEMENT_LABEL),
        )
        Text(
            text = stringResource(R.string.net_worth_restatement_note),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** The latest restated balance, the line, and every point as an exact row. */
private fun LazyListScope.trendItems(data: NetWorthData.Trend) {
    item(key = "hero") { NetWorthHero(data.currencyCode, data.latest.balanceDisplay) }
    item(key = "chart-header") { SectionHeader(stringResource(R.string.net_worth_chart_title)) }
    item(key = "chart") { TrendChart(data) }
    item(key = "table-header") { SectionHeader(stringResource(R.string.net_worth_table_title)) }
    itemsIndexed(data.points, key = { _, point -> "point-${point.date}" }) { index, point ->
        val formatDay = rememberDayFormatter(data.latest.date.year)
        GroupedLine(isFirst = index == 0, isLast = index == data.points.lastIndex) {
            ExactAmountRow(
                headline = formatDay(point.date),
                supporting = stringResource(R.string.net_worth_row_label),
                amount = point.balanceDisplay,
            )
        }
    }
}

/** The line with its labels and its description, all from the view model's formatted values. */
@Composable
private fun TrendChart(data: NetWorthData.Trend) {
    val formatDay = rememberDayFormatter(data.latest.date.year)
    val first = data.points.first()
    val start = formatDay(first.date)
    val end = formatDay(data.latest.date)
    val shape = stringResource(
        when (data.direction) {
            TrendDirection.UP -> if (data.crossesZero) R.string.net_worth_trend_up_crossing_zero else R.string.net_worth_trend_up
            TrendDirection.DOWN -> if (data.crossesZero) R.string.net_worth_trend_down_crossing_zero else R.string.net_worth_trend_down
            TrendDirection.FLAT -> if (data.crossesZero) R.string.net_worth_trend_flat_crossing_zero else R.string.net_worth_trend_flat
        },
    )
    val description = stringResource(
        R.string.net_worth_chart_description,
        bidiIsolate(start), bidiIsolate(first.balanceDisplay),
        bidiIsolate(end), bidiIsolate(data.latest.balanceDisplay),
        bidiIsolate(data.lowestDisplay), bidiIsolate(data.highestDisplay),
        shape,
    )
    val labels = LineChartLabels(
        highest = stringResource(R.string.net_worth_label_highest, bidiIsolate(data.highestDisplay)),
        lowest = stringResource(R.string.net_worth_label_lowest, bidiIsolate(data.lowestDisplay)),
        start = start,
        end = end,
    )
    NetWorthLineChart(data.points, labels, description, Modifier.testTag(ChartTags.NET_WORTH_CHART))
}

/** No line for this window: the exact balance now, and why there is no line. */
private fun LazyListScope.currentOnlyItems(data: NetWorthData.CurrentOnly) {
    item(key = "hero") { NetWorthHero(data.currencyCode, data.balanceDisplay) }
    item(key = "no-trend") {
        val reason = stringResource(
            when (data.reason) {
                NoTrendReason.START_TOO_OLD -> R.string.net_worth_reason_start_too_old
                NoTrendReason.TOO_FEW_POINTS -> R.string.net_worth_reason_too_few_points
            },
        )
        StatusCard(
            tone = StatusTone.INFO,
            message = stringResource(R.string.net_worth_no_trend, reason),
            modifier = Modifier.announced(),
        )
    }
    item(key = "table-header") { SectionHeader(stringResource(R.string.net_worth_table_title)) }
    item(key = "current") {
        GroupedLine(isFirst = true, isLast = true) {
            ExactAmountRow(
                headline = stringResource(R.string.net_worth_current_balance),
                supporting = stringResource(R.string.net_worth_restated),
                amount = data.balanceDisplay,
            )
        }
    }
}

@Composable
private fun NetWorthHero(currencyCode: String, amount: String) {
    ChartHero(
        heading = stringResource(R.string.net_worth_heading, currencyCode),
        amount = amount,
        caption = stringResource(R.string.net_worth_restated),
        modifier = Modifier.testTag(ChartTags.HERO),
    )
}
