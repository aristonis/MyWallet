package org.aristonis.mywallet.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.aristonis.mywallet.ui.account.AddAccountScreen
import org.aristonis.mywallet.ui.account.EditAccountScreen
import org.aristonis.mywallet.ui.account.ManageAccountsScreen
import org.aristonis.mywallet.ui.home.HomeScreen
import org.aristonis.mywallet.ui.onboarding.OnboardingScreen
import org.aristonis.mywallet.ui.rates.ManageRatesScreen
import org.aristonis.mywallet.ui.reports.ReportsScreen
import org.aristonis.mywallet.ui.settings.SettingsScreen
import org.aristonis.mywallet.ui.transaction.AddTransactionScreen
import org.aristonis.mywallet.ui.transaction.EditTransactionScreen
import org.aristonis.mywallet.ui.transaction.TransactionsListScreen
import org.aristonis.mywallet.ui.category.ManageCategoriesScreen
import androidx.compose.runtime.saveable.rememberSaveable
import org.aristonis.mywallet.ui.currency.ChangeBaseCurrencyScreen

/**
 * Top-level routing gate: shows onboarding until settings exist, then Home. Because it observes the
 * settings signal, finishing onboarding (which writes settings) automatically swaps to Home, and a
 * relaunch on an already-set-up device skips onboarding entirely.
 */
@Composable
fun AppRoot(viewModel: AppViewModel = hiltViewModel()) {
    val destination by viewModel.startDestination.collectAsStateWithLifecycle()
    when (destination) {
        StartDestination.LOADING -> LoadingScreen()
        StartDestination.ONBOARDING -> OnboardingScreen()
        StartDestination.HOME -> HomeFlow()
    }
}

/** The screens reachable from Home via the lightweight toggle below. */
private enum class HomeDestination { HOME, ADD_TRANSACTION, EDIT_TRANSACTION, TRANSACTIONS, REPORTS, ADD_ACCOUNT, MANAGE_ACCOUNTS, EDIT_ACCOUNT, MANAGE_RATES, MANAGE_CATEGORIES, CHANGE_BASE_CURRENCY, SETTINGS }

/**
 * Lightweight Home navigation via a `remember`ed destination. With this handful of screens a flag
 * beats pulling in a nav library; when a bottom-nav shell arrives this becomes a real NavHost. The
 * editors each need one argument (which transaction / which account), so that id rides alongside the
 * destination flag until that NavHost arrives with real route arguments.
 */
@Composable
private fun HomeFlow() {
    // Saved rather than merely remembered: a plain `remember` drops the user back to Home on every
    // rotation, losing whichever editor was open. A real back stack arrives with the NavHost.
    var destination by rememberSaveable { mutableStateOf(HomeDestination.HOME) }
    var editingTransactionId by rememberSaveable { mutableStateOf<Long?>(null) }
    var editingAccountId by rememberSaveable { mutableStateOf<Long?>(null) }
    val toHome = { destination = HomeDestination.HOME }
    val toManageAccounts = { destination = HomeDestination.MANAGE_ACCOUNTS }
    when (destination) {
        HomeDestination.HOME -> HomeScreen(
            onAddTransaction = { destination = HomeDestination.ADD_TRANSACTION },
            onTransactions = { destination = HomeDestination.TRANSACTIONS },
            onReports = { destination = HomeDestination.REPORTS },
            onAddAccount = { destination = HomeDestination.ADD_ACCOUNT },
            onManageAccounts = { destination = HomeDestination.MANAGE_ACCOUNTS },
            onManageRates = { destination = HomeDestination.MANAGE_RATES },
            onSettings = { destination = HomeDestination.SETTINGS },
        )
        HomeDestination.ADD_TRANSACTION -> AddTransactionScreen(onDone = toHome)
        HomeDestination.TRANSACTIONS -> TransactionsListScreen(
            onDone = toHome,
            onEditTransaction = { id ->
                editingTransactionId = id
                destination = HomeDestination.EDIT_TRANSACTION
            },
        )
        HomeDestination.EDIT_TRANSACTION -> {
            val id = editingTransactionId
            if (id == null) {
                // No row selected — nothing to edit, so bounce back rather than show a blank editor.
                LaunchedEffect(Unit) { destination = HomeDestination.HOME }
            } else {
                EditTransactionScreen(transactionId = id, onDone = toHome)
            }
        }
        HomeDestination.REPORTS -> ReportsScreen(onDone = toHome)
        HomeDestination.MANAGE_ACCOUNTS -> ManageAccountsScreen(
            onDone = toHome,
            onEditAccount = { id ->
                editingAccountId = id
                destination = HomeDestination.EDIT_ACCOUNT
            },
        )
        HomeDestination.EDIT_ACCOUNT -> {
            val id = editingAccountId
            if (id == null) {
                // No row selected — nothing to edit, so bounce back to the list rather than a blank editor.
                LaunchedEffect(Unit) { destination = HomeDestination.MANAGE_ACCOUNTS }
            } else {
                // The editor is launched from Manage Accounts, so it returns there (not Home) when done.
                EditAccountScreen(accountId = id, onDone = toManageAccounts)
            }
        }
        HomeDestination.ADD_ACCOUNT -> AddAccountScreen(onDone = toHome)
        HomeDestination.MANAGE_RATES -> ManageRatesScreen(onDone = toHome)
        HomeDestination.MANAGE_CATEGORIES -> ManageCategoriesScreen(onDone = { destination = HomeDestination.SETTINGS })
        HomeDestination.CHANGE_BASE_CURRENCY ->
            ChangeBaseCurrencyScreen(onDone = { destination = HomeDestination.SETTINGS })
        HomeDestination.SETTINGS -> SettingsScreen(
            onDone = toHome,
            onManageCategories = { destination = HomeDestination.MANAGE_CATEGORIES },
            onManageRates = { destination = HomeDestination.MANAGE_RATES },
            onChangeBaseCurrency = { destination = HomeDestination.CHANGE_BASE_CURRENCY },
        )
    }
}

@Composable
private fun LoadingScreen() {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        CircularProgressIndicator()
    }
}
