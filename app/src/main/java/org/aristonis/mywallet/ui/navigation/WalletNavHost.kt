package org.aristonis.mywallet.ui.navigation

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.hilt.navigation.compose.hiltViewModel
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
import org.aristonis.mywallet.ui.account.AccountDetailScreen
import org.aristonis.mywallet.ui.account.AddAccountScreen
import org.aristonis.mywallet.ui.account.EditAccountScreen
import org.aristonis.mywallet.ui.account.ManageAccountsScreen
import org.aristonis.mywallet.ui.category.ManageCategoriesScreen
import org.aristonis.mywallet.ui.currency.ChangeBaseCurrencyScreen
import org.aristonis.mywallet.ui.debt.DebtDetailScreen
import org.aristonis.mywallet.ui.debt.DebtsScreen
import org.aristonis.mywallet.ui.debt.RecordRepaymentScreen
import org.aristonis.mywallet.ui.home.HomeScreen
import org.aristonis.mywallet.ui.rates.ManageRatesScreen
import org.aristonis.mywallet.ui.reports.ReportsScreen
import org.aristonis.mywallet.ui.reports.charts.ChartWindowArgs
import org.aristonis.mywallet.ui.reports.charts.NetWorthScreen
import org.aristonis.mywallet.ui.reports.charts.SpendingBreakdownScreen
import org.aristonis.mywallet.ui.settings.AboutScreen
import org.aristonis.mywallet.ui.settings.AppLicenseScreen
import org.aristonis.mywallet.ui.settings.PrivacyInformationScreen
import org.aristonis.mywallet.ui.settings.SettingsScreen
import org.aristonis.mywallet.ui.settings.SupportFaqScreen
import org.aristonis.mywallet.ui.deduction.DeductionsScreen
import org.aristonis.mywallet.ui.deduction.DeductionEditorScreen
import org.aristonis.mywallet.ui.transaction.AddTransactionScreen
import org.aristonis.mywallet.ui.transaction.EditTransactionScreen
import org.aristonis.mywallet.ui.transaction.TransactionDetailScreen
import org.aristonis.mywallet.ui.transaction.TransactionsListScreen
import org.aristonis.mywallet.ui.transaction.TransactionsListViewModel

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
            // The bottom bar already sits above the system navigation bar, so the padding that clears
            // the bar clears that too. Marking it consumed stops each screen's own Scaffold from padding
            // for the system bar again, which left an empty strip above the bottom bar. A screen shown
            // without the bottom bar gets no padding here and still clears the system bar itself.
            modifier = Modifier.padding(innerPadding).consumeWindowInsets(innerPadding),
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

/** Optional starting points of the add form: a debt side, an account, and the Pay card transfer. */
private fun addTransactionArguments() = listOf(
    navArgument(ARG_DEBT_SIDE) { type = NavType.StringType; defaultValue = "" },
    navArgument(ARG_ACCOUNT_ID) { type = NavType.LongType; defaultValue = NO_ACCOUNT_ID },
    navArgument(ARG_PAY_CARD) { type = NavType.BoolType; defaultValue = false },
)

/** The account page and everything it opens, so a tab that lists accounts can open it. */
private fun NavGraphBuilder.accountDetailRoute(tab: WalletTab, nav: NavHostController) {
    editorRoute(tab, Leaf.ACCOUNT_DETAIL) {
        AccountDetailScreen(
            onBack = { nav.popBackStack() },
            onAddTransaction = { id -> nav.navigate(tab.addForAccountRoute(id)) },
            onPayCard = { id -> nav.navigate(tab.payCardRoute(id)) },
            onEditAccount = { id -> nav.navigate(tab.child(Leaf.EDIT_ACCOUNT, id)) },
            onOpenEntry = { id -> nav.navigate(tab.child(Leaf.TRANSACTION_DETAIL, id)) },
            onManageRates = { nav.navigate(tab.child(Leaf.RATES)) },
        )
    }
}

/** The read-only entry page; Edit opens the editor above it, and a delete closes the page. */
private fun NavGraphBuilder.transactionDetailRoute(tab: WalletTab, nav: NavHostController) {
    editorRoute(tab, Leaf.TRANSACTION_DETAIL) {
        TransactionDetailScreen(
            onBack = { nav.popBackStack() },
            onEdit = { id -> nav.navigate(tab.child(Leaf.EDIT_TRANSACTION, id)) },
            onDeleted = { nav.popBackStack() },
        )
    }
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
                onDebts = { nav.navigate(tab.child(Leaf.DEBTS)) },
                onOpenAccount = { id -> nav.navigate(tab.child(Leaf.ACCOUNT_DETAIL, id)) },
            )
        }
        // Home shows every date, so a saved entry is always in view; outside the Transactions graph the
        // hand-back only pops.
        composable(
            route = tab.addTransactionPattern(),
            arguments = addTransactionArguments(),
        ) {
            AddTransactionScreen(onDone = { nav.popBackStack() }, onSaved = { date -> nav.returnSavedDate(date) })
        }
        composable(tab.child(Leaf.ADD_ACCOUNT)) {
            AddAccountScreen(onDone = { nav.popBackStack() })
        }
        composable(tab.child(Leaf.MANAGE_ACCOUNTS)) {
            ManageAccountsScreen(
                onDone = { nav.popBackStack() },
                onAddAccount = { nav.navigate(tab.child(Leaf.ADD_ACCOUNT)) },
                onEditAccount = { id -> nav.navigate(tab.child(Leaf.EDIT_ACCOUNT, id)) },
                onOpenAccount = { id -> nav.navigate(tab.child(Leaf.ACCOUNT_DETAIL, id)) },
            )
        }
        editorRoute(tab, Leaf.EDIT_ACCOUNT) { id ->
            EditAccountScreen(accountId = id, onDone = { nav.popBackStack() })
        }
        accountDetailRoute(tab, nav)
        transactionDetailRoute(tab, nav)
        composable(tab.child(Leaf.RATES)) {
            ManageRatesScreen(onDone = { nav.popBackStack() })
        }
        composable(tab.child(Leaf.DEBTS)) {
            DebtsScreen(
                onDone = { nav.popBackStack() },
                onOpenDebt = { id -> nav.navigate(tab.child(Leaf.DEBT_DETAIL, id)) },
                onManageRates = { nav.navigate(tab.child(Leaf.RATES)) },
                onAddDebt = { side -> nav.navigate(tab.addDebtRoute(side)) },
            )
        }
        editorRoute(tab, Leaf.DEBT_DETAIL) { id ->
            DebtDetailScreen(
                debtId = id,
                onDone = { nav.popBackStack() },
                onEditEntry = { entryId -> nav.navigate(tab.child(Leaf.EDIT_TRANSACTION, entryId)) },
                onRecordRepayment = { debtId -> nav.navigate(tab.repaymentRoute(debtId)) },
                onEditRepayment = { debtId, repaymentId -> nav.navigate(tab.repaymentRoute(debtId, repaymentId)) },
            )
        }
        composable(
            route = tab.repaymentPattern(),
            arguments = listOf(
                navArgument(ARG_ID) { type = NavType.LongType },
                navArgument(ARG_REPAYMENT_ID) { type = NavType.LongType; defaultValue = NO_REPAYMENT_ID },
            ),
        ) { entry ->
            RecordRepaymentScreen(
                debtId = entry.requireId(),
                repaymentId = entry.arguments?.getLong(ARG_REPAYMENT_ID)?.takeIf { it != NO_REPAYMENT_ID },
                onDone = { nav.popBackStack() },
                onOpenEntry = { entryId ->
                    // The form is stale once the entry changes, so leave it and land back on the debt.
                    nav.popBackStack()
                    nav.navigate(tab.child(Leaf.EDIT_TRANSACTION, entryId))
                },
            )
        }
        editorRoute(tab, Leaf.EDIT_TRANSACTION) { id ->
            EditTransactionScreen(
                transactionId = id,
                onDone = { nav.popBackStack() },
                onSaved = { date -> nav.returnSavedDate(date) },
            )
        }
    }
}

private fun NavGraphBuilder.transactionsGraph(nav: NavHostController) {
    val tab = WalletTab.TRANSACTIONS
    navigation(startDestination = tab.rootRoute, route = tab.route) {
        // The list may be narrowed to some dates, so the editors hand back the date they saved on and
        // the list says so when the entry landed outside them.
        composable(tab.rootRoute) { entry ->
            val viewModel: TransactionsListViewModel = hiltViewModel()
            SavedDateEffect(entry, viewModel::onEntrySaved)
            TransactionsListScreen(
                onAddTransaction = { nav.navigate(tab.child(Leaf.ADD_TRANSACTION)) },
                onOpenTransaction = { id -> nav.navigate(tab.child(Leaf.TRANSACTION_DETAIL, id)) },
                viewModel = viewModel,
            )
        }
        composable(tab.child(Leaf.ADD_TRANSACTION)) {
            AddTransactionScreen(onDone = { nav.popBackStack() }, onSaved = { date -> nav.returnSavedDate(date) })
        }
        editorRoute(tab, Leaf.EDIT_TRANSACTION) { id ->
            EditTransactionScreen(
                transactionId = id,
                onDone = { nav.popBackStack() },
                onSaved = { date -> nav.returnSavedDate(date) },
            )
        }
        transactionDetailRoute(tab, nav)
    }
}

private fun NavGraphBuilder.trackingGraph(nav: NavHostController) {
    val tab = WalletTab.TRACKING
    navigation(startDestination = tab.rootRoute, route = tab.route) {
        composable(tab.rootRoute) {
            ReportsScreen(
                onManageRates = { nav.navigate(tab.child(Leaf.RATES)) },
                onOpenSpendingBreakdown = { window ->
                    nav.navigate(tab.child(Leaf.SPENDING_BREAKDOWN) + ChartWindowArgs.query(window))
                },
                onOpenNetWorth = { window -> nav.navigate(tab.child(Leaf.NET_WORTH) + ChartWindowArgs.query(window)) },
            )
        }
        composable(tab.child(Leaf.RATES)) {
            ManageRatesScreen(onDone = { nav.popBackStack() })
        }
        composable(
            tab.child(Leaf.SPENDING_BREAKDOWN) + ChartWindowArgs.QUERY_PATTERN,
            arguments = ChartWindowArgs.navArguments,
        ) {
            SpendingBreakdownScreen(
                onBack = { nav.popBackStack() },
                onManageRates = { nav.navigate(tab.child(Leaf.RATES)) },
            )
        }
        composable(
            tab.child(Leaf.NET_WORTH) + ChartWindowArgs.QUERY_PATTERN,
            arguments = ChartWindowArgs.navArguments,
        ) {
            NetWorthScreen(onBack = { nav.popBackStack() }, onManageRates = { nav.navigate(tab.child(Leaf.RATES)) })
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
                onManageDeductions = { nav.navigate(tab.child(Leaf.DEDUCTIONS)) },
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
                onOpenAccount = { id -> nav.navigate(tab.child(Leaf.ACCOUNT_DETAIL, id)) },
            )
        }
        editorRoute(tab, Leaf.EDIT_ACCOUNT) { id ->
            EditAccountScreen(accountId = id, onDone = { nav.popBackStack() })
        }
        accountDetailRoute(tab, nav)
        transactionDetailRoute(tab, nav)
        // Reached from an account's page, which can start an entry for that account.
        composable(route = tab.addTransactionPattern(), arguments = addTransactionArguments()) {
            AddTransactionScreen(onDone = { nav.popBackStack() }, onSaved = { date -> nav.returnSavedDate(date) })
        }
        editorRoute(tab, Leaf.EDIT_TRANSACTION) { id ->
            EditTransactionScreen(
                transactionId = id,
                onDone = { nav.popBackStack() },
                onSaved = { date -> nav.returnSavedDate(date) },
            )
        }
        composable(tab.child(Leaf.ADD_ACCOUNT)) {
            AddAccountScreen(onDone = { nav.popBackStack() })
        }
        composable(tab.child(Leaf.CATEGORIES)) {
            ManageCategoriesScreen(onDone = { nav.popBackStack() })
        }
        composable(tab.child(Leaf.DEDUCTIONS)) {
            DeductionsScreen(onDone = { nav.popBackStack() },
                onAdd = { nav.navigate(tab.child(Leaf.ADD_DEDUCTION)) },
                onEdit = { nav.navigate(tab.child(Leaf.EDIT_DEDUCTION, it)) })
        }
        composable(tab.child(Leaf.ADD_DEDUCTION)) {
            DeductionEditorScreen(isEditing = false, onDone = { nav.popBackStack() })
        }
        editorRoute(tab, Leaf.EDIT_DEDUCTION) {
            DeductionEditorScreen(isEditing = true, onDone = { nav.popBackStack() })
        }
        composable(tab.child(Leaf.RATES)) {
            ManageRatesScreen(onDone = { nav.popBackStack() })
        }
        composable(tab.child(Leaf.BASE_CURRENCY)) {
            ChangeBaseCurrencyScreen(onDone = { nav.popBackStack() })
        }
        composable(tab.child(Leaf.ABOUT)) {
            AboutScreen(
                onDone = { nav.popBackStack() },
                onLicense = { nav.navigate(tab.child(Leaf.APP_LICENSE)) },
                onPrivacy = { nav.navigate(tab.child(Leaf.PRIVACY)) },
                onSupport = { nav.navigate(tab.child(Leaf.SUPPORT)) },
            )
        }
        composable(tab.child(Leaf.APP_LICENSE)) {
            AppLicenseScreen(onDone = { nav.popBackStack() })
        }
        composable(tab.child(Leaf.PRIVACY)) {
            PrivacyInformationScreen(onDone = { nav.popBackStack() })
        }
        composable(tab.child(Leaf.SUPPORT)) {
            SupportFaqScreen(onDone = { nav.popBackStack() })
        }
    }
}
