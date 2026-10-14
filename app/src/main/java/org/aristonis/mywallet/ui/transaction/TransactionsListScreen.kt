package org.aristonis.mywallet.ui.transaction

// Amounts arrive pre-formatted from the view-model (per currency + locale); this screen only adds
// the sign each kind carries.

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.tooling.preview.PreviewParameter
import androidx.compose.ui.tooling.preview.PreviewParameterProvider
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.aristonis.mywallet.R
import org.aristonis.mywallet.domain.model.Money
import org.aristonis.mywallet.domain.model.TrackingPeriod
import org.aristonis.mywallet.domain.model.TrackingWindow
import org.aristonis.mywallet.ui.CategoryLabel
import org.aristonis.mywallet.ui.components.EmptyState
import org.aristonis.mywallet.ui.components.LoadFailedState
import org.aristonis.mywallet.ui.components.WalletTopAppBar
import org.aristonis.mywallet.ui.format.bidiIsolate
import org.aristonis.mywallet.ui.format.rememberDayFormatter
import org.aristonis.mywallet.ui.icons.WalletIcons
import org.aristonis.mywallet.ui.message.UiMessage
import org.aristonis.mywallet.ui.message.text
import org.aristonis.mywallet.ui.theme.MyWalletTheme
import org.aristonis.mywallet.ui.theme.WalletTheme
import org.aristonis.mywallet.ui.window.CompactDateWindowBar
import org.aristonis.mywallet.ui.window.DateWindowActions
import org.aristonis.mywallet.ui.window.RefreshOnStart
import java.time.LocalDate

/**
 * The history, newest first and grouped by day. Tapping a row opens it in the editor. This is a
 * top-level tab, so it has no back arrow — the navigation bar is how you leave it. The history loads
 * a page at a time; reaching the end of the rows asks for the next page.
 *
 * Every return to the screen re-reads the date, so a list left open overnight relabels its days and
 * a window following today moves along with it.
 */
@Composable
fun TransactionsListScreen(
    onAddTransaction: () -> Unit,
    onEditTransaction: (Long) -> Unit,
    viewModel: TransactionsListViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    RefreshOnStart { viewModel.onScreenStart() }
    TransactionsListContent(
        state = state,
        windowActions = viewModel.windowActions,
        onAddTransaction = onAddTransaction,
        onEditTransaction = onEditTransaction,
        onShowSavedEntry = viewModel::showSavedEntry,
        onSavedEntryMessageDone = viewModel::dismissSavedEntryMessage,
        onRetry = viewModel::retry,
        onLoadMore = viewModel::loadMore,
    )
}

/**
 * The date bar stays fixed under the app bar while the days scroll beneath it, so narrowing or
 * stepping the dates never means scrolling back to the top first.
 *
 * An entry just saved outside the dates gets a snackbar naming its day, with an action that moves the
 * dates onto it. [onShowSavedEntry] and [onSavedEntryMessageDone] report how the snackbar ended.
 * [onRetry] reads the dates in force again after a failed read. [onLoadMore] asks for the next page
 * once the end of the rows comes into view.
 */
@Composable
internal fun TransactionsListContent(
    state: TransactionsUiState,
    windowActions: DateWindowActions,
    onAddTransaction: () -> Unit,
    onEditTransaction: (Long) -> Unit,
    onShowSavedEntry: () -> Unit,
    onSavedEntryMessageDone: () -> Unit,
    onRetry: () -> Unit,
    onLoadMore: () -> Unit = {},
) {
    val dimensions = WalletTheme.dimensions
    val snackbarHostState = remember { SnackbarHostState() }
    SavedOutsideSnackbar(
        savedOn = state.savedOutsideWindow,
        snackbarHostState = snackbarHostState,
        onShow = onShowSavedEntry,
        onDone = onSavedEntryMessageDone,
    )
    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            WalletTopAppBar(title = stringResource(R.string.transactions_title))
        },
        bottomBar = {
            // Reserve the action's height outside the scrolling ledger so a visible row never sits
            // behind it; the list still has its own clearance after the final row.
            Box(
                modifier = Modifier.fillMaxWidth().padding(
                    horizontal = dimensions.screenPadding,
                    vertical = dimensions.relatedGap,
                ),
                contentAlignment = Alignment.CenterEnd,
            ) {
                ExtendedFloatingActionButton(onClick = onAddTransaction) {
                    Icon(imageVector = WalletIcons.Add, contentDescription = null)
                    Text(
                        text = stringResource(R.string.home_add_transaction),
                        modifier = Modifier.padding(start = dimensions.relatedGap),
                    )
                }
            }
        },
    ) { innerPadding ->
        Column(modifier = Modifier.padding(innerPadding).fillMaxSize()) {
            CompactDateWindowBar(
                window = state.window,
                actions = windowActions,
                modifier = Modifier.padding(horizontal = dimensions.screenPadding),
            )
            TransactionsBody(
                state = state,
                listBottomPadding = dimensions.screenPadding,
                onEditTransaction = onEditTransaction,
                onRetry = onRetry,
                onLoadMore = onLoadMore,
            )
        }
    }
}

/**
 * Says where an entry saved outside the dates went. Long rather than short: the user has to read a
 * date and decide whether to go to it, which takes longer than reading a confirmation.
 */
@Composable
private fun SavedOutsideSnackbar(
    savedOn: LocalDate?,
    snackbarHostState: SnackbarHostState,
    onShow: () -> Unit,
    onDone: () -> Unit,
) {
    val formatDay = rememberDayFormatter(currentYear = LocalDate.now().year)
    val message = savedOn?.let { stringResource(R.string.transactions_saved_outside, bidiIsolate(formatDay(it))) }
    val actionLabel = stringResource(R.string.action_show)
    val currentOnShow by rememberUpdatedState(onShow)
    val currentOnDone by rememberUpdatedState(onDone)
    val pending by rememberUpdatedState(savedOn)
    // A message not answered while the list was on screen is let go when the list leaves it (an editor
    // opened, another tab, a rotation), so no snackbar comes back later about an old save.
    DisposableEffect(Unit) {
        onDispose { if (pending != null) currentOnDone() }
    }
    LaunchedEffect(savedOn) {
        if (message == null) return@LaunchedEffect
        when (snackbarHostState.showSnackbar(message, actionLabel, duration = SnackbarDuration.Long)) {
            SnackbarResult.ActionPerformed -> currentOnShow()
            SnackbarResult.Dismissed -> currentOnDone()
        }
    }
}

@Composable
private fun TransactionsBody(
    state: TransactionsUiState,
    listBottomPadding: Dp,
    onEditTransaction: (Long) -> Unit,
    onRetry: () -> Unit,
    onLoadMore: () -> Unit,
) {
    val dimensions = WalletTheme.dimensions
    when {
        // Inline, under the date bar rather than instead of it, so the screen does not flicker
        // between two different layouts on every refresh.
        state.isLoading -> Column(modifier = Modifier.fillMaxSize().padding(dimensions.screenPadding)) {
            CircularProgressIndicator()
        }

        // Said in place of the list, under the date bar, so other dates can still be chosen; an empty
        // message here would claim there is nothing to show, which is not known.
        state.loadFailed -> Column(modifier = Modifier.fillMaxSize().padding(dimensions.screenPadding)) {
            // A row the app cannot read gets its own wording (the data is safe, other dates may show);
            // any other failure is the generic one.
            val message = when (val failure = state.loadFailure) {
                null, UiMessage.DataUnreadable -> stringResource(R.string.transactions_data_unreadable)
                else -> failure.text()
            }
            LoadFailedState(message = message, onRetry = onRetry)
        }

        // An empty filter is not an empty wallet: inviting a first transaction while the user's
        // history sits just outside the chosen dates would read as lost data.
        state.sections.isEmpty() -> Column(modifier = Modifier.fillMaxSize().padding(dimensions.screenPadding)) {
            val message = if (state.showsEverything) R.string.transactions_empty else R.string.transactions_empty_range
            EmptyState(message = stringResource(message))
        }

        else -> TransactionDays(
            sections = state.sections,
            listBottomPadding = listBottomPadding,
            hasMore = state.hasMore,
            isLoadingMore = state.isLoadingMore,
            rowCount = state.rowCount,
            onEditTransaction = onEditTransaction,
            onLoadMore = onLoadMore,
        )
    }
}

/**
 * The rows, keyed by entry id so the list keeps its place while pages arrive and entries change.
 * While [hasMore], a last item stands for the rows not loaded yet: it only composes when the end of
 * the list comes into view, and asks for the next page then. It asks again whenever a page has
 * finished loading and it is still in view, including after a request that was turned away.
 */
@Composable
private fun TransactionDays(
    sections: List<TransactionSection>,
    listBottomPadding: Dp,
    hasMore: Boolean,
    isLoadingMore: Boolean,
    rowCount: Int,
    onEditTransaction: (Long) -> Unit,
    onLoadMore: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val dimensions = WalletTheme.dimensions
    // Every header in the list shares one formatter, and "is this the current year" is decided once
    // for the whole list rather than re-derived per row.
    val formatDay = rememberDayFormatter(currentYear = LocalDate.now().year)

    // The final row needs scrollable space beyond the measured FAB, including at large font sizes.
    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = listBottomPadding),
    ) {
        sections.forEach { section ->
            item(key = "day-${section.date}") {
                DayHeader(text = dayHeaderText(section, formatDay))
            }
            items(section.rows, key = { it.id }) { row ->
                val isFirst = row.id == section.rows.first().id
                val isLast = row.id == section.rows.last().id
                val corners = RoundedCornerShape(
                    topStart = if (isFirst) dimensions.cornerMedium else 0.dp,
                    topEnd = if (isFirst) dimensions.cornerMedium else 0.dp,
                    bottomStart = if (isLast) dimensions.cornerMedium else 0.dp,
                    bottomEnd = if (isLast) dimensions.cornerMedium else 0.dp,
                )
                Column(
                    Modifier.fillMaxWidth().padding(horizontal = dimensions.screenPadding)
                        .background(MaterialTheme.colorScheme.surfaceContainer, corners),
                ) {
                    TransactionRowItem(row = row, onClick = { onEditTransaction(row.id) })
                    if (!isLast) {
                        HorizontalDivider(
                            modifier = Modifier.padding(horizontal = dimensions.cardPadding),
                            color = MaterialTheme.colorScheme.outlineVariant,
                        )
                    }
                }
            }
        }
        if (hasMore) {
            item(key = NEXT_PAGE_KEY) {
                val currentOnLoadMore by rememberUpdatedState(onLoadMore)
                LaunchedEffect(rowCount, isLoadingMore) {
                    if (!isLoadingMore) currentOnLoadMore()
                }
                Box(modifier = Modifier.fillMaxWidth().padding(dimensions.screenPadding), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            }
        }
    }
}

/** Key of the item that stands for the rows not loaded yet; no entry id can take it. */
private const val NEXT_PAGE_KEY = "next-page"

@Composable
private fun dayHeaderText(section: TransactionSection, formatDay: (LocalDate) -> String): String =
    when (section.relation) {
        // Today keeps its date alongside the word: it is the day a user checks against a receipt.
        DayRelation.TODAY -> stringResource(R.string.date_today, bidiIsolate(formatDay(section.date)))
        DayRelation.YESTERDAY -> stringResource(R.string.date_yesterday)
        DayRelation.EARLIER -> formatDay(section.date)
    }

@Composable
private fun DayHeader(text: String) {
    val dimensions = WalletTheme.dimensions
    Column(modifier = Modifier.fillMaxWidth().padding(
        start = dimensions.screenPadding,
        end = dimensions.screenPadding,
        top = dimensions.sectionGap,
        bottom = dimensions.relatedGap,
    )) {
        Text(
            text = text,
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

private class TransactionsPreviewSamples : PreviewParameterProvider<TransactionsUiState> {
    override val values: Sequence<TransactionsUiState> = sequenceOf(
            TransactionsUiState(
                isLoading = false,
                window = TrackingWindow.Period(TrackingPeriod.MONTH, LocalDate.of(2026, 8, 26)),
                sections = listOf(
                    TransactionSection(
                        date = LocalDate.of(2026, 8, 26),
                        relation = DayRelation.TODAY,
                        rows = listOf(
                            TransactionRow(
                                id = 1, date = LocalDate.of(2026, 8, 26), type = TransactionRowType.EXPENSE,
                                accountName = "Cash", destAccountName = null,
                                categoryLabel = CategoryLabel.Named("Food"),
                                amount = Money.of("12.50", "USD"), destAmount = null,
                                amountDisplay = "12.50 USD", destAmountDisplay = null, note = "Lunch",
                            ),
                            TransactionRow(
                                id = 2, date = LocalDate.of(2026, 8, 26), type = TransactionRowType.INCOME,
                                accountName = "Bank", destAccountName = null,
                                categoryLabel = CategoryLabel.Named("Salary"),
                                amount = Money.of("1500", "USD"), destAmount = null,
                                amountDisplay = "1,500.00 USD", destAmountDisplay = null, note = null,
                            ),
                        ),
                    ),
                    TransactionSection(
                        date = LocalDate.of(2026, 8, 25),
                        relation = DayRelation.YESTERDAY,
                        rows = listOf(
                            TransactionRow(
                                id = 3, date = LocalDate.of(2026, 8, 25), type = TransactionRowType.TRANSFER,
                                accountName = "Cash", destAccountName = "Euro Savings", categoryLabel = null,
                                amount = Money.of("11", "USD"), destAmount = Money.of("10.00", "EUR"),
                                amountDisplay = "11.00 USD", destAmountDisplay = "10.00 EUR", note = null,
                            ),
                        ),
                    ),
                ),
            ),
            TransactionsUiState(
                isLoading = false,
                window = TrackingWindow.Period(TrackingPeriod.MONTH, LocalDate.of(2026, 8, 26)),
            ),
            TransactionsUiState(
                isLoading = false,
                window = TrackingWindow.Period(TrackingPeriod.MONTH, LocalDate.of(2026, 8, 26)),
                loadFailed = true,
            ),
    )
}

@Preview(name = "Transactions light", showBackground = true, widthDp = 360)
@Preview(name = "Transactions dark", showBackground = true, widthDp = 360,
    uiMode = android.content.res.Configuration.UI_MODE_NIGHT_YES)
@Preview(name = "Transactions large text", showBackground = true, widthDp = 360, fontScale = 2f)
@Composable
private fun TransactionsListPreview(
    @PreviewParameter(TransactionsPreviewSamples::class) state: TransactionsUiState,
) {
    MyWalletTheme(dynamicColor = false) {
        TransactionsListContent(
            state = state,
            windowActions = DateWindowActions.None,
            onAddTransaction = {},
            onEditTransaction = {},
            onShowSavedEntry = {},
            onSavedEntryMessageDone = {},
            onRetry = {},
        )
    }
}
