package org.aristonis.mywallet.ui.reports

// Amounts arrive pre-formatted from the view-model (per currency + locale), so this screen never
// formats money itself.

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.aristonis.mywallet.R
import org.aristonis.mywallet.domain.model.TrackingPeriod
import org.aristonis.mywallet.domain.model.TrackingWindow
import org.aristonis.mywallet.ui.CategoryLabel
import org.aristonis.mywallet.ui.components.LoadFailedState
import org.aristonis.mywallet.ui.components.MoneyText
import org.aristonis.mywallet.ui.components.StatusCard
import org.aristonis.mywallet.ui.components.StatusTone
import org.aristonis.mywallet.ui.components.WalletTopAppBar
import org.aristonis.mywallet.ui.format.AmountRole
import org.aristonis.mywallet.ui.icons.WalletIcons
import org.aristonis.mywallet.ui.message.text
import org.aristonis.mywallet.ui.theme.MyWalletTheme
import org.aristonis.mywallet.ui.theme.WalletTheme
import org.aristonis.mywallet.ui.theme.amountColor
import org.aristonis.mywallet.ui.window.DateRangeAction
import org.aristonis.mywallet.ui.window.DateWindowActions
import org.aristonis.mywallet.ui.window.DateWindowBar
import org.aristonis.mywallet.ui.window.RefreshOnStart
import java.time.LocalDate
import kotlin.coroutines.cancellation.CancellationException

/** The narrowest width, after the text scale, at which Income and Expenses each keep a full amount. */
private val PAIR_ROW_MIN_WIDTH = 288.dp

/** The longest figure, in characters, that still fits a pair cell at 360dp; longer ones stack. */
private const val PAIR_ROW_MAX_FIGURE_CHARS = 15

/** Diameter of the role marker in each income and expense summary. */
private val SUMMARY_ROLE_MARKER_SIZE = 28.dp
private val SUMMARY_ROLE_ICON_SIZE = 18.dp
private const val SUMMARY_ROLE_MARKER_ALPHA = 0.16f

/**
 * Tracking: income / expense / net and both by-category breakdowns over a chosen span. Read-only.
 *
 * Every return to the screen, from another tab or from the background, gives the view-model a
 * chance to notice that midnight has passed, so "this month" never quietly turns into last month.
 *
 * [onOpenSpendingBreakdown] and [onOpenNetWorth] open the chart screens on the window shown here.
 * Each link appears only when its callback is given.
 */
@Composable
fun ReportsScreen(
    onManageRates: () -> Unit,
    onOpenSpendingBreakdown: ((TrackingWindow) -> Unit)? = null,
    onOpenNetWorth: ((TrackingWindow) -> Unit)? = null,
    viewModel: ReportsViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    RefreshOnStart { viewModel.onScreenStart() }
    val exportState by viewModel.exportState.collectAsStateWithLifecycle()
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) {
        viewModel.completeExport(it)
    }
    LaunchedEffect(exportState) {
        val ready = exportState as? ReportsExportState.Ready ?: return@LaunchedEffect
        viewModel.onExportPickerLaunched()
        try {
            launcher.launch(ready.fileName)
        } catch (error: Exception) {
            if (error is CancellationException) throw error
            viewModel.onExportPickerFailed(error)
        }
    }
    ReportsContent(state = state, windowActions = viewModel.windowActions, onRetry = viewModel::retry,
        onManageRates = onManageRates,
        exportState = exportState, onExport = viewModel::prepareExport,
        onExportResultShown = viewModel::consumeExportResult,
        onOpenSpendingBreakdown = onOpenSpendingBreakdown, onOpenNetWorth = onOpenNetWorth)
}

/**
 * The date bar stays fixed above a bounded list, so stepping to another month never means scrolling
 * back up to find the arrows.
 *
 * Which categories are open lives here rather than in the list: a step to a month with a missing
 * rate swaps the list for a warning, and stepping back should find the same categories still open.
 *
 * A failed read is said under the date bar rather than instead of it, so other dates can still be
 * chosen. [onRetry] reads the dates in force again.
 */
@Composable
internal fun ReportsContent(
    state: ReportsUiState,
    windowActions: DateWindowActions,
    onRetry: () -> Unit,
    onManageRates: () -> Unit,
    exportState: ReportsExportState,
    onExport: () -> Unit,
    onExportResultShown: () -> Unit,
    onOpenSpendingBreakdown: ((TrackingWindow) -> Unit)? = null,
    onOpenNetWorth: ((TrackingWindow) -> Unit)? = null,
) {
    val snackbar = remember { SnackbarHostState() }
    val exportMessage = when (exportState) {
        ReportsExportState.Success -> stringResource(R.string.tracking_export_saved)
        is ReportsExportState.Error -> exportState.message.text()
        else -> null
    }
    LaunchedEffect(exportMessage) {
        if (exportMessage != null) {
            snackbar.showSnackbar(exportMessage)
            onExportResultShown()
        }
    }
    val expanded = rememberSaveable(stateSaver = ExpandedIdsSaver) { mutableStateOf(emptySet<Long>()) }
    val onToggle: (Long) -> Unit = remember(expanded) {
        { id -> expanded.value = if (id in expanded.value) expanded.value - id else expanded.value + id }
    }
    val dimensions = WalletTheme.dimensions
    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            WalletTopAppBar(
                title = stringResource(R.string.tracking_title),
                actions = {
                    DateRangeAction(window = state.window, onSelectRange = windowActions.onSelectRange)
                    TrackingExportMenu(
                        enabled = !state.loadFailed && state.data is ReportsData.Ready &&
                            exportState == ReportsExportState.Idle,
                        onExport = onExport,
                    )
                },
            )
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .padding(innerPadding)
                .padding(dimensions.screenPadding)
                .fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(dimensions.regionGap),
        ) {
            DateWindowBar(window = state.window, actions = windowActions)

            if (state.loadFailed) {
                LoadFailedState(message = stringResource(R.string.tracking_data_unreadable), onRetry = onRetry)
                return@Column
            }

            val chartLinks: @Composable () -> Unit = {
                TrackingChartLinks(state.window, onOpenSpendingBreakdown, onOpenNetWorth)
            }
            val showLinks = hasChartLinks(onOpenSpendingBreakdown, onOpenNetWorth)
            when (val data = state.data) {
                ReportsData.Loading -> CircularProgressIndicator()

                is ReportsData.MissingRate -> {
                    MissingRateNotice(data.currencyCode, onManageRates)
                    if (showLinks) chartLinks()
                }

                is ReportsData.Ready -> if (data.isEmpty) {
                    EmptyWindowNote()
                    if (showLinks) chartLinks()
                } else {
                    ReportList(
                        data = data,
                        expandedIds = expanded.value,
                        onToggle = onToggle,
                        modifier = Modifier.weight(1f),
                        chartLinks = chartLinks.takeIf { showLinks },
                    )
                }
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
    chartLinks: (@Composable () -> Unit)? = null,
) {
    LazyColumn(modifier = modifier) {
        item(key = "summary") { SummaryMetrics(data) }
        if (chartLinks != null) {
            item(key = "chart-links") {
                Box(Modifier.padding(top = WalletTheme.dimensions.regionGap)) { chartLinks() }
            }
        }
        categorySection(
            section = CategorySection.EXPENSE,
            title = R.string.reports_spending_by_category,
            emptyText = R.string.reports_no_spending,
            rows = data.expenseCategories,
            expandedIds = expandedIds,
            onToggle = onToggle,
        )
        categorySection(
            section = CategorySection.INCOME,
            title = R.string.reports_income_by_category,
            emptyText = R.string.reports_no_income,
            rows = data.incomeCategories,
            expandedIds = expandedIds,
            onToggle = onToggle,
        )
    }
}

/**
 * One summary card read like the sum it is: money in and money out side by side (stacked when the
 * figures or the text are large), then the net they leave below a divider.
 */
@Composable
private fun SummaryMetrics(data: ReportsData.Ready) {
    val dimensions = WalletTheme.dimensions
    val income = stringResource(R.string.reports_income)
    val expenses = stringResource(R.string.reports_expenses)
    val net = stringResource(R.string.reports_net)
    val noFigure = ""
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val sideBySide = maxWidth / LocalDensity.current.fontScale >= PAIR_ROW_MIN_WIDTH &&
            maxOf(data.incomeDisplay.length, data.expenseDisplay.length) <= PAIR_ROW_MAX_FIGURE_CHARS
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
            shape = MaterialTheme.shapes.large,
        ) {
            Column(modifier = Modifier.fillMaxWidth()) {
                if (sideBySide) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(dimensions.cardPadding),
                        horizontalArrangement = Arrangement.spacedBy(dimensions.relatedGap),
                    ) {
                        SummaryRoleMetric(
                            label = income,
                            value = data.incomeDisplay,
                            missingText = noFigure,
                            role = AmountRole.INCOME,
                            modifier = Modifier.weight(1f),
                        )
                        SummaryRoleMetric(
                            label = expenses,
                            value = data.expenseDisplay,
                            missingText = noFigure,
                            role = AmountRole.EXPENSE,
                            modifier = Modifier.weight(1f),
                        )
                    }
                } else {
                    Column(
                        modifier = Modifier.fillMaxWidth().padding(dimensions.cardPadding),
                        verticalArrangement = Arrangement.spacedBy(dimensions.relatedGap),
                    ) {
                        SummaryRoleMetric(
                            income,
                            data.incomeDisplay,
                            noFigure,
                            AmountRole.INCOME,
                            Modifier.fillMaxWidth(),
                        )
                        SummaryRoleMetric(
                            expenses,
                            data.expenseDisplay,
                            noFigure,
                            AmountRole.EXPENSE,
                            Modifier.fillMaxWidth(),
                        )
                    }
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                Column(
                    modifier = Modifier.fillMaxWidth().padding(dimensions.cardPadding),
                    verticalArrangement = Arrangement.spacedBy(dimensions.grid),
                ) {
                    Text(
                        text = net,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    MoneyText(
                        amount = data.netDisplay,
                        style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Bold),
                    )
                }
            }
        }
    }
}

/**
 * A merged income or expense summary: the direction icon and its caption on one line, the figure under
 * them at the half's full width, so a long amount never has to share its line with the icon.
 */
@Composable
private fun SummaryRoleMetric(
    label: String,
    value: String?,
    missingText: String,
    role: AmountRole,
    modifier: Modifier = Modifier,
) {
    val dimensions = WalletTheme.dimensions
    val roleColor = amountColor(role)
    val icon = if (role == AmountRole.INCOME) WalletIcons.Income else WalletIcons.Expense
    // Merged so TalkBack reads the caption and the figure as one item.
    Column(
        modifier = modifier.semantics(mergeDescendants = true) {},
        verticalArrangement = Arrangement.spacedBy(dimensions.relatedGap),
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(dimensions.relatedGap),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(SUMMARY_ROLE_MARKER_SIZE)
                    .clip(CircleShape)
                    .background(roleColor.copy(alpha = SUMMARY_ROLE_MARKER_ALPHA)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = roleColor,
                    modifier = Modifier.size(SUMMARY_ROLE_ICON_SIZE),
                )
            }
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        MoneyText(
            amount = value ?: missingText,
            modifier = Modifier.fillMaxWidth(),
            style = MaterialTheme.typography.titleMedium,
            color = roleColor,
        )
    }
}

/** A rate the report needs is missing: say which, and offer the screen that fixes it. */
@Composable
private fun MissingRateNotice(currencyCode: String, onManageRates: () -> Unit) {
    val dimensions = WalletTheme.dimensions
    Column(verticalArrangement = Arrangement.spacedBy(dimensions.relatedGap)) {
        StatusCard(
            tone = StatusTone.WARNING,
            message = stringResource(R.string.reports_missing_rate, currencyCode),
            iconDescription = stringResource(R.string.cd_warning),
        )
        TextButton(onClick = onManageRates, modifier = Modifier.heightIn(min = dimensions.minTouchTarget)) {
            Text(stringResource(R.string.tracking_manage_rates))
        }
    }
}

/** The window has no income and no spending: one note, not a zero summary over two empty lists. */
@Composable
private fun EmptyWindowNote() {
    val dimensions = WalletTheme.dimensions
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceContainer, RoundedCornerShape(dimensions.cornerMedium))
            .padding(dimensions.cardPadding),
        verticalArrangement = Arrangement.spacedBy(dimensions.relatedGap),
    ) {
        Text(stringResource(R.string.tracking_empty_title), style = MaterialTheme.typography.titleMedium)
        Text(
            stringResource(R.string.tracking_empty_hint),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

private val PREVIEW_WINDOW = TrackingWindow.Period(TrackingPeriod.MONTH, LocalDate.of(2026, 8, 10))

private val PREVIEW_READY = ReportsData.Ready(
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
                SubCategoryRow(12, CategoryLabel.Named("Dining"), "100.00 USD"),
                SubCategoryRow(null, CategoryLabel.NoSubCategory, "20.00 USD"),
            ),
        ),
        CategoryRow(2, CategoryLabel.Named("Transport"), "410.50 USD", subCategories = emptyList()),
        CategoryRow(3, CategoryLabel.Named("Other"), "245.00 USD", subCategories = emptyList()),
    ),
)

@Composable
private fun PreviewScreen(data: ReportsData, dark: Boolean = false) {
    MyWalletTheme(darkTheme = dark, dynamicColor = false) {
        ReportsContent(
            state = ReportsUiState(window = PREVIEW_WINDOW, data = data),
            windowActions = DateWindowActions.None,
            onRetry = {},
            onManageRates = {},
            exportState = ReportsExportState.Idle,
            onExport = {},
            onExportResultShown = {},
        )
    }
}

@Preview(showBackground = true, widthDp = 360, heightDp = 720)
@Composable
private fun ReportsPreview() = PreviewScreen(PREVIEW_READY)

@Preview(showBackground = true, widthDp = 360, heightDp = 720, uiMode = android.content.res.Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun ReportsDarkPreview() = PreviewScreen(PREVIEW_READY, dark = true)

@Preview(showBackground = true, widthDp = 360, heightDp = 720, fontScale = 2f)
@Composable
private fun ReportsLargeTextPreview() = PreviewScreen(PREVIEW_READY)

@Preview(showBackground = true, widthDp = 360, heightDp = 480)
@Composable
private fun ReportsEmptyPreview() = PreviewScreen(PREVIEW_READY.copy(incomeCategories = emptyList(), expenseCategories = emptyList()))

@Preview(showBackground = true, widthDp = 360, heightDp = 480)
@Composable
private fun ReportsMissingRatePreview() = PreviewScreen(ReportsData.MissingRate("EUR"))
