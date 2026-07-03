package org.aristonis.mywallet.ui.home

// UI copy hardcoded; localizing strings (RTL/i18n) comes later. Money shown as "amount CODE";
// proper per-currency symbol + locale formatting comes later too.

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExtendedFloatingActionButton
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
import org.aristonis.mywallet.domain.model.Account
import org.aristonis.mywallet.domain.model.AccountBalanceInBase
import org.aristonis.mywallet.domain.model.AccountTypeRegistry
import org.aristonis.mywallet.domain.model.Money
import org.aristonis.mywallet.ui.theme.MyWalletTheme

@Composable
fun HomeScreen(
    onAddTransaction: () -> Unit,
    onTransactions: () -> Unit,
    onAddAccount: () -> Unit,
    onManageRates: () -> Unit,
    viewModel: HomeViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    HomeContent(state, onAddTransaction, onTransactions, onAddAccount, onManageRates)
}

@Composable
private fun HomeContent(
    state: HomeUiState,
    onAddTransaction: () -> Unit,
    onTransactions: () -> Unit,
    onAddAccount: () -> Unit,
    onManageRates: () -> Unit,
) {
    Scaffold(
        floatingActionButton = {
            // Recording a transaction is the primary action, so it gets the FAB. Adding an account is
            // a rarer, setup-time action, so it moves to a text button by the accounts list.
            ExtendedFloatingActionButton(onClick = onAddTransaction) { Text("Add transaction") }
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier.padding(innerPadding).fillMaxSize().padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("My Wallet", style = MaterialTheme.typography.headlineMedium, modifier = Modifier.weight(1f))
                TextButton(onClick = onTransactions) { Text("History") }
            }
            NetWorthHero(state.netWorth, onManageRates)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Accounts", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                TextButton(onClick = onAddAccount) { Text("Add account") }
            }
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(state.accounts) { accountBalance -> AccountCard(accountBalance, onManageRates) }
            }
        }
    }
}

@Composable
private fun NetWorthHero(netWorth: NetWorthState, onManageRates: () -> Unit) {
    when (netWorth) {
        NetWorthState.Loading -> CircularProgressIndicator()

        is NetWorthState.Amount -> Column {
            Text("Net worth", style = MaterialTheme.typography.labelMedium)
            Text(netWorth.total.display(), style = MaterialTheme.typography.headlineLarge)
        }

        is NetWorthState.MissingRate -> Card(
            modifier = Modifier.fillMaxWidth().clickable(onClick = onManageRates),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
        ) {
            Text(
                "Set an exchange rate for ${netWorth.currencyCode} to see your net worth.",
                modifier = Modifier.padding(16.dp),
                color = MaterialTheme.colorScheme.onErrorContainer,
            )
        }
    }
}

@Composable
private fun AccountCard(row: AccountBalanceInBase, onManageRates: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(row.account.name, style = MaterialTheme.typography.titleMedium)
            Text(typeName(row.account.typeKey), style = MaterialTheme.typography.bodySmall)
            Text(row.native.display(), style = MaterialTheme.typography.titleLarge)
            val base = row.base // local val so Kotlin can smart-cast after the null check
            when {
                // Missing rate: show a tappable prompt (opens the rates screen), not a fabricated number.
                base == null -> Text(
                    "Set a rate for ${row.native.currencyCode} to convert",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.clickable(onClick = onManageRates),
                )
                // Different currency: show the base-converted amount too.
                base.currencyCode != row.native.currencyCode -> Text(
                    "≈ ${base.display()}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                // Account already in the base currency: native == base, no second line.
            }
        }
    }
}

private fun typeName(typeKey: String): String =
    AccountTypeRegistry.BuiltIns.all.firstOrNull { it.key == typeKey }?.displayName ?: typeKey

/** Placeholder formatting: amount + ISO code. Per-currency symbol + locale formatting comes later. */
private fun Money.display(): String = "${amount.toPlainString()} $currencyCode"

@Preview(showBackground = true)
@Composable
private fun HomePreview() {
    MyWalletTheme(dynamicColor = false) {
        HomeContent(
            onAddTransaction = {},
            onTransactions = {},
            onAddAccount = {},
            onManageRates = {},
            state = HomeUiState(
                netWorth = NetWorthState.Amount(Money.of("1275.00", "USD")),
                accounts = listOf(
                    AccountBalanceInBase(
                        Account(id = 1, name = "Cash", typeKey = "cash", currencyCode = "USD", openingBalance = Money.of("1000", "USD")),
                        native = Money.of("1000", "USD"),
                        base = Money.of("1000", "USD"),
                    ),
                    AccountBalanceInBase(
                        Account(id = 2, name = "Euro Savings", typeKey = "savings", currencyCode = "EUR", openingBalance = Money.of("250", "EUR")),
                        native = Money.of("250", "EUR"),
                        base = Money.of("275.00", "USD"),
                    ),
                ),
                baseCurrencyCode = "USD",
            ),
        )
    }
}
