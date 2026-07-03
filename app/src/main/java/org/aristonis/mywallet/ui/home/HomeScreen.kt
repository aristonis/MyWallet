package org.aristonis.mywallet.ui.home

// UI copy hardcoded; string externalization (RTL/i18n) is the SG-13 pass. Money shown as
// "amount CODE" — proper per-currency symbol + locale formatting lands in SG-5.

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.aristonis.mywallet.domain.model.Account
import org.aristonis.mywallet.domain.model.AccountBalance
import org.aristonis.mywallet.domain.model.AccountTypeRegistry
import org.aristonis.mywallet.domain.model.Money
import org.aristonis.mywallet.ui.theme.MyWalletTheme

@Composable
fun HomeScreen(viewModel: HomeViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    HomeContent(state)
}

@Composable
private fun HomeContent(state: HomeUiState) {
    Scaffold { innerPadding ->
        Column(
            modifier = Modifier.padding(innerPadding).fillMaxSize().padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text("My Wallet", style = MaterialTheme.typography.headlineMedium)
            NetWorthHero(state.netWorth)
            Text("Accounts", style = MaterialTheme.typography.titleMedium)
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(state.accounts) { accountBalance -> AccountCard(accountBalance) }
            }
        }
    }
}

@Composable
private fun NetWorthHero(netWorth: NetWorthState) {
    when (netWorth) {
        NetWorthState.Loading -> CircularProgressIndicator()

        is NetWorthState.Amount -> Column {
            Text("Net worth", style = MaterialTheme.typography.labelMedium)
            Text(netWorth.total.display(), style = MaterialTheme.typography.headlineLarge)
        }

        is NetWorthState.MissingRate -> Card(
            modifier = Modifier.fillMaxWidth(),
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
private fun AccountCard(accountBalance: AccountBalance) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(accountBalance.account.name, style = MaterialTheme.typography.titleMedium)
            Text(typeName(accountBalance.account.typeKey), style = MaterialTheme.typography.bodySmall)
            Text(accountBalance.balance.display(), style = MaterialTheme.typography.titleLarge)
        }
    }
}

private fun typeName(typeKey: String): String =
    AccountTypeRegistry.BuiltIns.all.firstOrNull { it.key == typeKey }?.displayName ?: typeKey

/** Placeholder formatting: amount + ISO code. SG-5 replaces this with per-currency symbol + locale. */
private fun Money.display(): String = "${amount.toPlainString()} $currencyCode"

@Preview(showBackground = true)
@Composable
private fun HomePreview() {
    MyWalletTheme(dynamicColor = false) {
        HomeContent(
            HomeUiState(
                netWorth = NetWorthState.Amount(Money.of("1250.00", "USD")),
                accounts = listOf(
                    AccountBalance(
                        Account(id = 1, name = "Cash", typeKey = "cash", currencyCode = "USD", openingBalance = Money.of("1250", "USD")),
                        Money.of("1250", "USD"),
                    ),
                ),
                baseCurrencyCode = "USD",
            ),
        )
    }
}
