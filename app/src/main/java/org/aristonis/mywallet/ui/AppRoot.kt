package org.aristonis.mywallet.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.aristonis.mywallet.ui.account.AddAccountScreen
import org.aristonis.mywallet.ui.home.HomeScreen
import org.aristonis.mywallet.ui.onboarding.OnboardingScreen
import org.aristonis.mywallet.ui.rates.ManageRatesScreen
import org.aristonis.mywallet.ui.transaction.AddTransactionScreen
import org.aristonis.mywallet.ui.transaction.TransactionsListScreen

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
private enum class HomeDestination { HOME, ADD_TRANSACTION, TRANSACTIONS, ADD_ACCOUNT, MANAGE_RATES }

/**
 * Lightweight Home navigation via a `remember`ed destination. With this handful of screens a flag
 * beats pulling in a nav library; when a bottom-nav shell arrives this becomes a real NavHost.
 */
@Composable
private fun HomeFlow() {
    var destination by remember { mutableStateOf(HomeDestination.HOME) }
    val toHome = { destination = HomeDestination.HOME }
    when (destination) {
        HomeDestination.HOME -> HomeScreen(
            onAddTransaction = { destination = HomeDestination.ADD_TRANSACTION },
            onTransactions = { destination = HomeDestination.TRANSACTIONS },
            onAddAccount = { destination = HomeDestination.ADD_ACCOUNT },
            onManageRates = { destination = HomeDestination.MANAGE_RATES },
        )
        HomeDestination.ADD_TRANSACTION -> AddTransactionScreen(onDone = toHome)
        HomeDestination.TRANSACTIONS -> TransactionsListScreen(onDone = toHome)
        HomeDestination.ADD_ACCOUNT -> AddAccountScreen(onDone = toHome)
        HomeDestination.MANAGE_RATES -> ManageRatesScreen(onDone = toHome)
    }
}

@Composable
private fun LoadingScreen() {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        CircularProgressIndicator()
    }
}
