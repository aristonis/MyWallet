package org.aristonis.mywallet.ui.home

// Amounts arrive pre-formatted from the view-model (per currency + locale), so this screen never
// formats money itself — it only decides how the finished text is drawn.

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.aristonis.mywallet.R
import org.aristonis.mywallet.domain.model.Account
import org.aristonis.mywallet.domain.model.Money
import org.aristonis.mywallet.ui.account.accountTypeLabel
import org.aristonis.mywallet.ui.components.EmptyState
import org.aristonis.mywallet.ui.components.LoadFailedState
import org.aristonis.mywallet.ui.components.MoneyText
import org.aristonis.mywallet.ui.components.SectionHeader
import org.aristonis.mywallet.ui.components.StatusCard
import org.aristonis.mywallet.ui.components.StatusTone
import org.aristonis.mywallet.ui.components.WalletListRow
import org.aristonis.mywallet.ui.components.WalletTopAppBar
import org.aristonis.mywallet.ui.format.bidiIsolate
import org.aristonis.mywallet.ui.icons.WalletIcons
import org.aristonis.mywallet.ui.icons.accountTypeIcon
import org.aristonis.mywallet.ui.theme.MyWalletTheme
import org.aristonis.mywallet.ui.theme.WalletTheme

@Composable
fun HomeScreen(
    onAddTransaction: () -> Unit,
    onAddAccount: () -> Unit,
    onManageAccounts: () -> Unit,
    onManageRates: () -> Unit,
    onSettings: () -> Unit,
    onDebts: () -> Unit,
    viewModel: HomeViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    HomeContent(
        state = state,
        onAddTransaction = onAddTransaction,
        onAddAccount = onAddAccount,
        onManageAccounts = onManageAccounts,
        onManageRates = onManageRates,
        onSettings = onSettings,
        onDebts = onDebts,
        onRetry = viewModel::retry,
    )
}

/** [onRetry] reads the wallet again after a failed read. */
@Composable
internal fun HomeContent(
    state: HomeUiState,
    onAddTransaction: () -> Unit,
    onAddAccount: () -> Unit,
    onManageAccounts: () -> Unit,
    onManageRates: () -> Unit,
    onSettings: () -> Unit,
    onDebts: () -> Unit,
    onRetry: () -> Unit,
) {
    Scaffold(
        topBar = {
            WalletTopAppBar(title = stringResource(R.string.home_title)) {
                TextButton(onClick = onDebts) { Text(stringResource(R.string.debts_title)) }
                IconButton(onClick = onSettings) {
                    Icon(
                        imageVector = WalletIcons.Settings,
                        contentDescription = stringResource(R.string.home_settings),
                    )
                }
            }
        },
        floatingActionButton = {
            // Recording a transaction is the primary action, so it gets the FAB. Adding an account is
            // a rarer, setup-time action, so it lives behind Manage.
            ExtendedFloatingActionButton(onClick = onAddTransaction) {
                Icon(imageVector = WalletIcons.Add, contentDescription = null)
                Text(
                    text = stringResource(R.string.home_add_transaction),
                    modifier = Modifier.padding(start = 8.dp),
                )
            }
        },
    ) { innerPadding ->
        // One list, not a column wrapping a list: the hero scrolls away with the accounts, and the
        // accounts get a real height instead of asking for the sum of all their rows.
        LazyColumn(
            modifier = Modifier.padding(innerPadding).fillMaxSize(),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 88.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            // A failed read has no figure coming, so no spinner promising one; the message below says why.
            if (!state.loadFailed) {
                item(key = "balance") {
                    Column(modifier = Modifier.padding(bottom = 16.dp)) {
                        HomeSummaryHero(state.summary, onManageRates, onDebts)
                    }
                }
            }
            if (!state.loadFailed && state.debtsBelowZero.isNotEmpty()) {
                item(key = "negative-debts") {
                    StatusCard(StatusTone.WARNING, stringResource(R.string.home_debts_below_zero),
                        iconDescription = stringResource(R.string.cd_warning), onClick = onDebts)
                }
            }
            item(key = "accounts-header") {
                SectionHeader(title = stringResource(R.string.home_accounts)) {
                    if (state.accounts.isNotEmpty()) {
                        TextButton(onClick = onManageAccounts) { Text(stringResource(R.string.home_manage)) }
                    }
                }
            }
            when {
                // A failed read is not an empty wallet: offering a first account would suggest the
                // user's accounts are gone.
                state.loadFailed -> item(key = "accounts-unreadable") {
                    LoadFailedState(
                        message = stringResource(R.string.home_data_unreadable),
                        onRetry = onRetry,
                        modifier = Modifier.padding(top = 16.dp),
                    )
                }

                // Before the first read an empty list means "unknown", not "no accounts", so no prompt
                // yet. The summary above already shows the screen is loading; one spinner is enough.
                !state.isLoading && state.accounts.isEmpty() -> item(key = "accounts-empty") {
                    EmptyState(
                        message = stringResource(R.string.home_no_accounts),
                        actionLabel = stringResource(R.string.home_create_first_account),
                        onAction = onAddAccount,
                        modifier = Modifier.padding(top = 16.dp),
                    )
                }

                else -> items(state.accounts, key = { it.account.id }) { row ->
                    AccountCard(row, onManageRates)
                }
            }
        }
    }
}

/**
 * The summary the screen exists to show. The balance gets the full width and the display type size;
 * the pocket, owed-to-me and I-owe figures below it are the working detail behind that number. A
 * missing rate is recoverable and the card is the way to recover it: tapping opens the rates screen.
 */
@Composable
private fun HomeSummaryHero(summary: HomeSummaryState, onManageRates: () -> Unit, onDebts: () -> Unit) {
    when (summary) {
        HomeSummaryState.Loading -> CircularProgressIndicator()
        is HomeSummaryState.MissingRate -> StatusCard(StatusTone.WARNING,
            stringResource(R.string.home_missing_rate, summary.currencyCode),
            iconDescription = stringResource(R.string.cd_warning), onClick = onManageRates)
        is HomeSummaryState.Amounts -> Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer,
                contentColor = MaterialTheme.colorScheme.onPrimaryContainer),
        ) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.home_balance), style = MaterialTheme.typography.labelMedium)
                MoneyText(summary.balanceDisplay, style = MaterialTheme.typography.displaySmall)
                SummaryAmount(stringResource(R.string.home_in_my_pocket), summary.pocketDisplay)
                SummaryAmount(stringResource(R.string.home_owed_to_me), summary.owedToMeDisplay, onDebts)
                SummaryAmount(stringResource(R.string.home_i_owe), summary.iOweDisplay, onDebts)
            }
        }
    }
}

@Composable
private fun SummaryAmount(label: String, amount: String, onClick: (() -> Unit)? = null) {
    val tappable = if (onClick != null) Modifier.clickable(role = Role.Button, onClick = onClick) else Modifier
    Column(Modifier.fillMaxWidth().then(tappable)) {
        Text(label, style = MaterialTheme.typography.bodyMedium)
        MoneyText(amount, style = MaterialTheme.typography.titleMedium)
    }
}

@Composable
private fun AccountCard(row: AccountRow, onManageRates: () -> Unit) {
    val base = row.base // local val so Kotlin can smart-cast after the null check
    Card(modifier = Modifier.fillMaxWidth()) {
        WalletListRow(headline = row.account.name,
            supporting = stringResource(R.string.account_type_and_currency,
                accountTypeLabel(row.account.typeKey), row.account.currencyCode),
            leadingIcon = accountTypeIcon(row.account.typeKey),
            // Only tappable when there is something to fix; otherwise the row is a read-only figure.
            onClick = if (base == null) onManageRates else null)
        Column(Modifier.padding(start = 16.dp, end = 16.dp, bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)) {
            MoneyText(row.nativeDisplay, style = MaterialTheme.typography.titleMedium)
            when {
                // A missing rate shows a prompt, never a fabricated number.
                base == null -> TextButton(onClick = onManageRates) {
                    Text(stringResource(R.string.home_account_missing_rate, row.native.currencyCode),
                        color = WalletTheme.colors.warning)
                }
                // Held in another currency: show what it is worth in the base one too. When it is already
                // in the base currency a second line would repeat, so there is none.
                base.currencyCode != row.native.currencyCode -> MoneyText(
                    stringResource(R.string.home_account_converted, row.baseDisplay.orEmpty()),
                    style = MaterialTheme.typography.bodySmall)
            }
            CreditLines(row)
        }
    }
}

/** The credit card lines: what is owed, what is still available, and a warning when over the limit. */
@Composable
private fun CreditLines(row: AccountRow) {
    row.owedDisplay?.let { Text(stringResource(R.string.home_credit_owed, bidiIsolate(it))) }
    row.availableDisplay?.let { Text(stringResource(R.string.home_credit_available, bidiIsolate(it))) }
    if (row.credit?.overLimit == true) {
        StatusCard(StatusTone.WARNING,
            stringResource(R.string.home_credit_over_limit, bidiIsolate(row.overLimitDisplay.orEmpty())),
            iconDescription = stringResource(R.string.cd_warning))
    }
}

@Preview(showBackground = true)
@Composable
private fun HomePreview() {
    MyWalletTheme(dynamicColor = false) {
        HomeContent(
            onAddTransaction = {},
            onAddAccount = {},
            onManageAccounts = {},
            onManageRates = {},
            onSettings = {},
            onDebts = {},
            state = HomeUiState(
                summary = HomeSummaryState.Amounts("1,275.00 USD", "0.00 USD", "0.00 USD", "1,275.00 USD"),
                accounts = listOf(
                    AccountRow(
                        account = Account(id = 1, name = "Cash", typeKey = "cash", currencyCode = "USD", openingBalance = Money.of("1000", "USD")),
                        native = Money.of("1000", "USD"),
                        base = Money.of("1000", "USD"),
                        nativeDisplay = "1,000.00 USD",
                        baseDisplay = "1,000.00 USD",
                    ),
                    AccountRow(
                        account = Account(id = 2, name = "Euro Savings", typeKey = "bank", currencyCode = "EUR", openingBalance = Money.of("250", "EUR")),
                        native = Money.of("250", "EUR"),
                        base = Money.of("275.00", "USD"),
                        nativeDisplay = "250.00 EUR",
                        baseDisplay = "275.00 USD",
                    ),
                ),
                baseCurrencyCode = "USD",
            ),
            onRetry = {},
        )
    }
}
