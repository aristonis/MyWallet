package org.aristonis.mywallet.ui.transaction

// Amounts arrive pre-formatted from the view-model (per currency + locale); this screen only adds
// the sign each kind carries.

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.aristonis.mywallet.R
import org.aristonis.mywallet.domain.model.Money
import org.aristonis.mywallet.domain.model.TrackingPeriod
import org.aristonis.mywallet.domain.model.TrackingWindow
import org.aristonis.mywallet.ui.CategoryLabel
import org.aristonis.mywallet.ui.components.EmptyState
import org.aristonis.mywallet.ui.components.MoneyText
import org.aristonis.mywallet.ui.components.WalletListRow
import org.aristonis.mywallet.ui.components.WalletTopAppBar
import org.aristonis.mywallet.ui.format.amountRole
import org.aristonis.mywallet.ui.format.bidiIsolate
import org.aristonis.mywallet.ui.format.rememberDayFormatter
import org.aristonis.mywallet.ui.format.signedAmount
import org.aristonis.mywallet.ui.icons.WalletIcons
import org.aristonis.mywallet.ui.text
import org.aristonis.mywallet.ui.theme.MyWalletTheme
import org.aristonis.mywallet.ui.theme.amountColor
import org.aristonis.mywallet.ui.window.DateRangeAction
import org.aristonis.mywallet.ui.window.DateWindowActions
import org.aristonis.mywallet.ui.window.DateWindowBar
import org.aristonis.mywallet.ui.window.RefreshOnStart
import java.time.LocalDate

/** Gap between the date bar and the screen edges, matching the rest of the screen's content. */
private val SCREEN_PADDING = 16.dp

/**
 * The history, newest first and grouped by day. Tapping a row opens it in the editor. This is a
 * top-level tab, so it has no back arrow — the navigation bar is how you leave it.
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
    )
}

/**
 * The date bar stays fixed under the app bar while the days scroll beneath it, so narrowing or
 * stepping the dates never means scrolling back to the top first.
 *
 * An entry just saved outside the dates gets a snackbar naming its day, with an action that moves the
 * dates onto it. [onShowSavedEntry] and [onSavedEntryMessageDone] report how the snackbar ended.
 */
@Composable
internal fun TransactionsListContent(
    state: TransactionsUiState,
    windowActions: DateWindowActions,
    onAddTransaction: () -> Unit,
    onEditTransaction: (Long) -> Unit,
    onShowSavedEntry: () -> Unit,
    onSavedEntryMessageDone: () -> Unit,
) {
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
            WalletTopAppBar(
                title = stringResource(R.string.transactions_title),
                actions = { DateRangeAction(window = state.window, onSelectRange = windowActions.onSelectRange) },
            )
        },
        floatingActionButton = {
            // The same action as Home's, in the same place: recording a transaction is what the user
            // came here to do, whichever of the two lists they are looking at.
            ExtendedFloatingActionButton(onClick = onAddTransaction) {
                Icon(imageVector = WalletIcons.Add, contentDescription = null)
                Text(
                    text = stringResource(R.string.home_add_transaction),
                    modifier = Modifier.padding(start = 8.dp),
                )
            }
        },
    ) { innerPadding ->
        Column(modifier = Modifier.padding(innerPadding).fillMaxSize()) {
            DateWindowBar(
                window = state.window,
                actions = windowActions,
                modifier = Modifier.padding(horizontal = SCREEN_PADDING),
            )
            TransactionsBody(state = state, onEditTransaction = onEditTransaction)
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
private fun TransactionsBody(state: TransactionsUiState, onEditTransaction: (Long) -> Unit) {
    when {
        // Inline, under the date bar rather than instead of it, so the screen does not flicker
        // between two different layouts on every refresh.
        state.isLoading -> Column(modifier = Modifier.fillMaxSize().padding(SCREEN_PADDING)) {
            CircularProgressIndicator()
        }

        // Said in place of the list, under the date bar, so other dates can still be chosen; an empty
        // message here would claim there is nothing to show, which is not known.
        state.loadFailed -> Column(modifier = Modifier.fillMaxSize().padding(SCREEN_PADDING)) {
            EmptyState(message = stringResource(R.string.transactions_data_unreadable))
        }

        // An empty filter is not an empty wallet: inviting a first transaction while the user's
        // history sits just outside the chosen dates would read as lost data.
        state.sections.isEmpty() -> Column(modifier = Modifier.fillMaxSize().padding(SCREEN_PADDING)) {
            val message = if (state.showsEverything) R.string.transactions_empty else R.string.transactions_empty_range
            EmptyState(message = stringResource(message))
        }

        else -> TransactionDays(sections = state.sections, onEditTransaction = onEditTransaction)
    }
}

@Composable
private fun TransactionDays(
    sections: List<TransactionSection>,
    onEditTransaction: (Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    // Every header in the list shares one formatter, and "is this the current year" is decided once
    // for the whole list rather than re-derived per row.
    val formatDay = rememberDayFormatter(currentYear = LocalDate.now().year)

    LazyColumn(modifier = modifier.fillMaxSize()) {
        sections.forEach { section ->
            item(key = "day-${section.date}") {
                DayHeader(text = dayHeaderText(section, formatDay))
            }
            items(section.rows, key = { it.id }) { row ->
                TransactionRowItem(row = row, onClick = { onEditTransaction(row.id) })
            }
        }
    }
}

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
    Column(modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 24.dp)) {
        Text(
            text = text,
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        HorizontalDivider(modifier = Modifier.padding(top = 8.dp))
    }
}

@Composable
private fun TransactionRowItem(row: TransactionRow, onClick: () -> Unit) {
    val role = row.type.amountRole()
    WalletListRow(
        headline = headline(row),
        supporting = supporting(row),
        leadingIcon = row.type.icon(),
        leadingIconDescription = row.type.iconDescription(),
        leadingIconTint = amountColor(role),
        trailing = {
            MoneyText(
                amount = amountLine(row),
                style = MaterialTheme.typography.titleMedium,
                color = amountColor(role),
            )
        },
        onClick = onClick,
    )
}

/** What the row is about: the category for a spend or an income, the route for a transfer. */
@Composable
private fun headline(row: TransactionRow): String {
    val missing = stringResource(R.string.value_missing)
    if (row.type == TransactionRowType.TRANSFER) {
        // Two names with an arrow between them: without isolation a right-to-left layout can swap
        // which account the arrow points at, turning a transfer into its opposite.
        return stringResource(
            R.string.transfer_route,
            bidiIsolate(row.accountName ?: missing),
            bidiIsolate(row.destAccountName ?: missing),
        )
    }
    val category = row.categoryLabel?.text() ?: missing
    // The note is what tells two otherwise identical rows apart, so it belongs on the first line.
    return row.note?.let { stringResource(R.string.transaction_headline_with_note, category, it) } ?: category
}

/** The quieter second line: which account the money moved through. */
@Composable
private fun supporting(row: TransactionRow): String? = when (row.type) {
    TransactionRowType.INCOME, TransactionRowType.EXPENSE -> row.accountName ?: stringResource(R.string.value_missing)
    TransactionRowType.TRANSFER -> row.note
}

@Composable
private fun TransactionRowType.icon() = when (this) {
    TransactionRowType.INCOME -> WalletIcons.Income
    TransactionRowType.EXPENSE -> WalletIcons.Expense
    TransactionRowType.TRANSFER -> WalletIcons.Transfer
}

/** The icon carries the kind, so it is announced rather than left as decoration. */
@Composable
private fun TransactionRowType.iconDescription() = stringResource(
    when (this) {
        TransactionRowType.INCOME -> R.string.cd_income
        TransactionRowType.EXPENSE -> R.string.cd_expense
        TransactionRowType.TRANSFER -> R.string.cd_transfer
    },
)

@Composable
private fun amountLine(row: TransactionRow): String = when (row.type) {
    // A same-currency transfer moves one figure between two accounts, so showing it twice would say
    // nothing. A cross-currency one genuinely has two amounts, and hiding either would misstate it.
    TransactionRowType.TRANSFER ->
        if (row.destAmountDisplay != null && row.destAmountDisplay != row.amountDisplay) {
            stringResource(
                R.string.transfer_amounts,
                bidiIsolate(row.amountDisplay),
                bidiIsolate(row.destAmountDisplay),
            )
        } else {
            row.amountDisplay
        }
    TransactionRowType.INCOME, TransactionRowType.EXPENSE -> signedAmount(row.type.amountRole(), row.amountDisplay)
}

@Preview(showBackground = true)
@Composable
private fun TransactionsListPreview() {
    MyWalletTheme(dynamicColor = false) {
        TransactionsListContent(
            state = TransactionsUiState(
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
            windowActions = DateWindowActions.None,
            onAddTransaction = {},
            onEditTransaction = {},
            onShowSavedEntry = {},
            onSavedEntryMessageDone = {},
        )
    }
}
