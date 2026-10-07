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
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.aristonis.mywallet.R
import org.aristonis.mywallet.domain.model.Money
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
import java.time.LocalDate

/**
 * The history, newest first and grouped by day. Tapping a row opens it in the editor. This is a
 * top-level tab, so it has no back arrow — the navigation bar is how you leave it.
 */
@Composable
fun TransactionsListScreen(
    onAddTransaction: () -> Unit,
    onEditTransaction: (Long) -> Unit,
    viewModel: TransactionsListViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    TransactionsListContent(
        state = state,
        onAddTransaction = onAddTransaction,
        onEditTransaction = onEditTransaction,
    )
}

@Composable
private fun TransactionsListContent(
    state: TransactionsUiState,
    onAddTransaction: () -> Unit,
    onEditTransaction: (Long) -> Unit,
) {
    Scaffold(
        topBar = { WalletTopAppBar(title = stringResource(R.string.transactions_title)) },
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
        when {
            // Inline, under the app bar rather than instead of it, so the screen does not flicker
            // between two different layouts on every refresh.
            state.isLoading -> Column(
                modifier = Modifier.padding(innerPadding).fillMaxSize().padding(16.dp),
            ) {
                CircularProgressIndicator()
            }

            state.sections.isEmpty() -> Column(
                modifier = Modifier.padding(innerPadding).fillMaxSize().padding(16.dp),
            ) {
                EmptyState(message = stringResource(R.string.transactions_empty))
            }

            else -> TransactionDays(
                sections = state.sections,
                onEditTransaction = onEditTransaction,
                modifier = Modifier.padding(innerPadding),
            )
        }
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
    TransactionRowType.TRANSFER -> row.note
    else -> row.accountName ?: stringResource(R.string.value_missing)
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
    else -> signedAmount(row.type.amountRole(), row.amountDisplay)
}

@Preview(showBackground = true)
@Composable
private fun TransactionsListPreview() {
    MyWalletTheme(dynamicColor = false) {
        TransactionsListContent(
            state = TransactionsUiState(
                isLoading = false,
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
            onAddTransaction = {},
            onEditTransaction = {},
        )
    }
}
