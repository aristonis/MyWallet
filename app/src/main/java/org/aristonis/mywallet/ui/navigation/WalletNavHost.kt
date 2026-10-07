package org.aristonis.mywallet.ui.navigation

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.navigation
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import org.aristonis.mywallet.ui.account.AddAccountScreen
import org.aristonis.mywallet.ui.account.EditAccountScreen
import org.aristonis.mywallet.ui.account.ManageAccountsScreen
import org.aristonis.mywallet.ui.category.ManageCategoriesScreen
import org.aristonis.mywallet.ui.currency.ChangeBaseCurrencyScreen
import org.aristonis.mywallet.ui.home.HomeScreen
import org.aristonis.mywallet.ui.rates.ManageRatesScreen
import org.aristonis.mywallet.ui.reports.ReportsScreen
import org.aristonis.mywallet.ui.settings.AboutScreen
import org.aristonis.mywallet.ui.settings.SettingsScreen
import org.aristonis.mywallet.ui.transaction.AddTransactionScreen
import org.aristonis.mywallet.ui.transaction.EditTransactionScreen
import org.aristonis.mywallet.ui.transaction.TransactionsListScreen

/**
 * The tabbed shell.
 *
 * Every screen lives in exactly one tab's nested graph, so pushing one and coming back lands where
 * the user actually was. Screens are reached by route rather than by a flag held next to the caller,
 * which is what lets each editor take its row id as an argument: the id then survives rotation and
 * process death with the back stack instead of alongside it.
 */
@Composable
fun WalletNavHost(
    navController: NavHostController = rememberNavController(),
    // Taking the graph as a parameter is what lets the shell be tested on its own: a test supplies
    // stub destinations at the real routes and exercises the tabs, the back stack and the argument
    // plumbing without standing up the app's dependency graph and a real database behind it.
    graph: NavGraphBuilder.(NavHostController) -> Unit = { nav -> walletGraph(nav) },
) {
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route

    Scaffold(
        bottomBar = {
            if (showsNavigationBar(currentRoute)) {
                WalletNavigationBar(selected = tabOf(currentRoute), onSelect = navController::switchTab)
            }
        },
        // Each screen brings its own Scaffold and handles its own system-bar insets; without this the
        // outer one would inset the content a second time and leave a band under every app bar.
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
    ) { innerPadding ->
        NavHost(
            navController = navController,
            startDestination = WalletTab.HOME.route,
            modifier = Modifier.padding(innerPadding),
        ) {
            graph(navController)
        }
    }
}

/** The app's own destinations. */
internal fun NavGraphBuilder.walletGraph(nav: NavHostController) {
    homeGraph(nav)
    transactionsGraph(nav)
    trackingGraph(nav)
    settingsGraph(nav)
}

/**
 * Move to another tab without stacking tabs on top of each other.
 *
 * `saveState`/`restoreState` are what make a tab remember where it was: the stack being left behind
 * is put aside rather than discarded, so returning re-enters that tab mid-flow instead of at its top.
 */
internal fun NavHostController.switchTab(tab: WalletTab) {
    navigate(tab.route) {
        popUpTo(graph.startDestinationId) { saveState = true }
        launchSingleTop = true
        restoreState = true
    }
}

/** The row id an editor route was opened with. */
internal fun NavBackStackEntry.requireId(): Long =
    checkNotNull(arguments?.getLong(ARG_ID)) { "editor route reached with no $ARG_ID argument" }

internal fun NavGraphBuilder.editorRoute(
    tab: WalletTab,
    leaf: String,
    content: @Composable (Long) -> Unit,
) {
    composable(
        route = tab.childPattern(leaf),
        arguments = listOf(navArgument(ARG_ID) { type = NavType.LongType }),
    ) { entry -> content(entry.requireId()) }
}

private fun NavGraphBuilder.homeGraph(nav: NavHostController) {
    val tab = WalletTab.HOME
    navigation(startDestination = tab.rootRoute, route = tab.route) {
        composable(tab.rootRoute) {
            HomeScreen(
                onAddTransaction = { nav.navigate(tab.child(Leaf.ADD_TRANSACTION)) },
                onAddAccount = { nav.navigate(tab.child(Leaf.ADD_ACCOUNT)) },
                onManageAccounts = { nav.navigate(tab.child(Leaf.MANAGE_ACCOUNTS)) },
                onManageRates = { nav.navigate(tab.child(Leaf.RATES)) },
                onSettings = { nav.switchTab(WalletTab.SETTINGS) },
            )
        }
        composable(tab.child(Leaf.ADD_TRANSACTION)) {
            AddTransactionScreen(onDone = { nav.popBackStack() })
        }
        composable(tab.child(Leaf.ADD_ACCOUNT)) {
            AddAccountScreen(onDone = { nav.popBackStack() })
        }
        composable(tab.child(Leaf.MANAGE_ACCOUNTS)) {
            ManageAccountsScreen(
                onDone = { nav.popBackStack() },
                onAddAccount = { nav.navigate(tab.child(Leaf.ADD_ACCOUNT)) },
                onEditAccount = { id -> nav.navigate(tab.child(Leaf.EDIT_ACCOUNT, id)) },
            )
        }
        editorRoute(tab, Leaf.EDIT_ACCOUNT) { id ->
            EditAccountScreen(accountId = id, onDone = { nav.popBackStack() })
        }
        composable(tab.child(Leaf.RATES)) {
            ManageRatesScreen(onDone = { nav.popBackStack() })
        }
    }
}

private fun NavGraphBuilder.transactionsGraph(nav: NavHostController) {
    val tab = WalletTab.TRANSACTIONS
    navigation(startDestination = tab.rootRoute, route = tab.route) {
        composable(tab.rootRoute) {
            TransactionsListScreen(
                onAddTransaction = { nav.navigate(tab.child(Leaf.ADD_TRANSACTION)) },
                onEditTransaction = { id -> nav.navigate(tab.child(Leaf.EDIT_TRANSACTION, id)) },
            )
        }
        composable(tab.child(Leaf.ADD_TRANSACTION)) {
            AddTransactionScreen(onDone = { nav.popBackStack() })
        }
        editorRoute(tab, Leaf.EDIT_TRANSACTION) { id ->
            EditTransactionScreen(transactionId = id, onDone = { nav.popBackStack() })
        }
    }
}

private fun NavGraphBuilder.trackingGraph(nav: NavHostController) {
    val tab = WalletTab.TRACKING
    navigation(startDestination = tab.rootRoute, route = tab.route) {
        composable(tab.rootRoute) { ReportsScreen() }
        composable(tab.child(Leaf.RATES)) {
            ManageRatesScreen(onDone = { nav.popBackStack() })
        }
    }
}

private fun NavGraphBuilder.settingsGraph(nav: NavHostController) {
    val tab = WalletTab.SETTINGS
    navigation(startDestination = tab.rootRoute, route = tab.route) {
        composable(tab.rootRoute) {
            SettingsScreen(
                onManageAccounts = { nav.navigate(tab.child(Leaf.MANAGE_ACCOUNTS)) },
                onManageCategories = { nav.navigate(tab.child(Leaf.CATEGORIES)) },
                onManageRates = { nav.navigate(tab.child(Leaf.RATES)) },
                onChangeBaseCurrency = { nav.navigate(tab.child(Leaf.BASE_CURRENCY)) },
                onAbout = { nav.navigate(tab.child(Leaf.ABOUT)) },
            )
        }
        composable(tab.child(Leaf.MANAGE_ACCOUNTS)) {
            ManageAccountsScreen(
                onDone = { nav.popBackStack() },
                onAddAccount = { nav.navigate(tab.child(Leaf.ADD_ACCOUNT)) },
                onEditAccount = { id -> nav.navigate(tab.child(Leaf.EDIT_ACCOUNT, id)) },
            )
        }
        editorRoute(tab, Leaf.EDIT_ACCOUNT) { id ->
            EditAccountScreen(accountId = id, onDone = { nav.popBackStack() })
        }
        composable(tab.child(Leaf.ADD_ACCOUNT)) {
            AddAccountScreen(onDone = { nav.popBackStack() })
        }
        composable(tab.child(Leaf.CATEGORIES)) {
            ManageCategoriesScreen(onDone = { nav.popBackStack() })
        }
        composable(tab.child(Leaf.RATES)) {
            ManageRatesScreen(onDone = { nav.popBackStack() })
        }
        composable(tab.child(Leaf.BASE_CURRENCY)) {
            ChangeBaseCurrencyScreen(onDone = { nav.popBackStack() })
        }
        composable(tab.child(Leaf.ABOUT)) {
            AboutScreen(onDone = { nav.popBackStack() })
        }
    }
}
