package org.aristonis.mywallet.ui.navigation

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.compose.composable
import androidx.navigation.compose.navigation
import androidx.navigation.compose.rememberNavController
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.aristonis.mywallet.ui.theme.MyWalletTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The shell itself: the real [WalletNavHost], the real routes from [WalletTab]/[Leaf], and the real
 * `editorRoute` argument plumbing — with stub destinations in place of the screens, so what is under
 * test is the navigation and not the app's database and dependency graph.
 *
 * These are the behaviours a user would notice immediately if they broke, and none of them can be
 * checked by looking at a single screen.
 */
@RunWith(AndroidJUnit4::class)
class WalletNavHostTest {

    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private lateinit var nav: NavHostController

    /** A destination that says which one it is, and can push the supporting screens under its tab. */
    @Composable
    private fun Stub(name: String, pushes: List<Pair<String, String>> = emptyList()) {
        Column {
            Text(name)
            pushes.forEach { (label, route) ->
                Button(onClick = { nav.navigate(route) }) { Text(label) }
            }
        }
    }

    private fun NavGraphBuilder.stubTab(
        tab: WalletTab,
        rootPushes: List<Pair<String, String>> = emptyList(),
        extras: NavGraphBuilder.() -> Unit = {},
    ) {
        navigation(startDestination = tab.rootRoute, route = tab.route) {
            composable(tab.rootRoute) {
                Stub(
                    name = "${tab.route} root",
                    pushes = listOf("open rates from ${tab.route}" to tab.child(Leaf.RATES)) + rootPushes,
                )
            }
            composable(tab.child(Leaf.RATES)) { Stub("${tab.route} rates") }
            composable(tab.child(Leaf.ADD_TRANSACTION)) { Stub("${tab.route} add transaction") }
            extras()
        }
    }

    private fun start() {
        compose.setContent {
            MyWalletTheme(dynamicColor = false) {
                nav = rememberNavController()
                WalletNavHost(navController = nav) { _ ->
                    stubTab(
                        WalletTab.HOME,
                        rootPushes = listOf("open debts" to WalletTab.HOME.child(Leaf.DEBTS)),
                    ) {
                        composable(WalletTab.HOME.child(Leaf.DEBTS)) {
                            Stub("home debts", listOf("open debt" to WalletTab.HOME.child(Leaf.DEBT_DETAIL, 4_294_967_296L)))
                        }
                        editorRoute(WalletTab.HOME, Leaf.DEBT_DETAIL) { id ->
                            Stub("debt $id", listOf("open debt history entry" to WalletTab.HOME.child(Leaf.EDIT_TRANSACTION, 7)))
                        }
                        editorRoute(WalletTab.HOME, Leaf.EDIT_TRANSACTION) { id ->
                            Stub("editing debt history entry $id")
                        }
                    }
                    stubTab(WalletTab.TRANSACTIONS) {
                        editorRoute(WalletTab.TRANSACTIONS, Leaf.EDIT_TRANSACTION) { id ->
                            Stub("editing transaction $id")
                        }
                    }
                    stubTab(WalletTab.TRACKING)
                    stubTab(WalletTab.SETTINGS)
                }
            }
        }
    }

    private fun pressBack() {
        compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }
        compose.waitForIdle()
    }

    @Test
    fun allFourTabsAreReachable() {
        start()

        compose.onNodeWithText("home root").assertIsDisplayed()

        compose.onNodeWithText("Transactions").performClick()
        compose.onNodeWithText("transactions root").assertIsDisplayed()

        compose.onNodeWithText("Tracking").performClick()
        compose.onNodeWithText("tracking root").assertIsDisplayed()

        compose.onNodeWithText("Settings").performClick()
        compose.onNodeWithText("settings root").assertIsDisplayed()

        compose.onNodeWithText("Home").performClick()
        compose.onNodeWithText("home root").assertIsDisplayed()
    }

    /** Back from a child restores its caller tab and its navigation bar. */
    @Test
    fun aSupportingScreenHidesTheBarUntilBack() {
        start()

        compose.onNodeWithText("open rates from home").performClick()

        compose.onNodeWithText("home rates").assertIsDisplayed()
        compose.onNodeWithText("Tracking").assertDoesNotExist()
        pressBack()
        compose.onNodeWithText("home root").assertIsDisplayed()
        compose.onNodeWithText("Tracking").assertIsDisplayed()
    }

    /** A form owns its bottom edge; nothing else sits under its committing button. */
    @Test
    fun theNavigationBarIsHiddenOnAForm() {
        start()

        compose.runOnUiThread { nav.navigate(WalletTab.HOME.child(Leaf.ADD_TRANSACTION)) }
        compose.waitForIdle()

        compose.onNodeWithText("home add transaction").assertIsDisplayed()
        compose.onNodeWithText("Tracking").assertDoesNotExist()
    }

    @Test
    fun backFromASupportingScreenReturnsToTheTabItWasOpenedFrom() {
        start()

        compose.onNodeWithText("Settings").performClick()
        compose.onNodeWithText("open rates from settings").performClick()
        compose.onNodeWithText("settings rates").assertIsDisplayed()

        pressBack()

        // Back returns to Settings, not to Home: the rates screen was pushed onto the Settings stack.
        compose.onNodeWithText("settings root").assertIsDisplayed()
        compose.onNodeWithText("Settings").assertIsSelected()
    }

    /** The navigation controller still saves each tab's child stack during a tab switch. */
    @Test
    fun switchingTabsRestoresTheStackLeftBehind() {
        start()

        compose.onNodeWithText("open rates from home").performClick()
        compose.onNodeWithText("home rates").assertIsDisplayed()

        compose.runOnUiThread { nav.switchTab(WalletTab.TRACKING) }
        compose.waitForIdle()
        compose.onNodeWithText("tracking root").assertIsDisplayed()

        compose.runOnUiThread { nav.switchTab(WalletTab.HOME) }
        compose.waitForIdle()

        compose.onNodeWithText("home rates").assertIsDisplayed()
    }

    /** Two tabs can show the same screen; each keeps its own copy, so Back stays truthful. */
    @Test
    fun theSameSupportingScreenIsSeparatePerTab() {
        start()

        compose.onNodeWithText("open rates from home").performClick()
        compose.onNodeWithText("home rates").assertIsDisplayed()

        pressBack()
        compose.onNodeWithText("Settings").performClick()
        compose.onNodeWithText("open rates from settings").performClick()

        compose.onNodeWithText("settings rates").assertIsDisplayed()
    }

    @Test
    fun anEditorReceivesItsIdFromTheRoute() {
        start()

        compose.onNodeWithText("Transactions").performClick()
        compose.runOnUiThread {
            nav.navigate(WalletTab.TRANSACTIONS.child(Leaf.EDIT_TRANSACTION, id = 4_294_967_296L))
        }
        compose.waitForIdle()

        // A Long, not an Int: a wallet that has churned through rows hands out ids past Int range,
        // and a truncated one would open the wrong transaction.
        compose.onNodeWithText("editing transaction 4294967296").assertIsDisplayed()
    }

    @Test
    fun backFromAnEditorReturnsToItsTab() {
        start()

        compose.onNodeWithText("Transactions").performClick()
        compose.runOnUiThread { nav.navigate(WalletTab.TRANSACTIONS.child(Leaf.EDIT_TRANSACTION, id = 7L)) }
        compose.waitForIdle()
        compose.onNodeWithText("editing transaction 7").assertIsDisplayed()

        pressBack()

        compose.onNodeWithText("transactions root").assertIsDisplayed()
    }

    @Test
    fun debtHistoryEditorReturnsToDetailThenListThenHome() {
        start()

        compose.onNodeWithText("open debts").performClick()
        compose.onNodeWithText("home debts").assertIsDisplayed()
        compose.onNodeWithText("Tracking").assertDoesNotExist()
        compose.onNodeWithText("open debt").performClick()
        compose.onNodeWithText("debt 4294967296").assertIsDisplayed()
        compose.onNodeWithText("open debt history entry").performClick()
        compose.onNodeWithText("editing debt history entry 7").assertIsDisplayed()
        compose.onNodeWithText("Tracking").assertDoesNotExist()

        pressBack()
        compose.onNodeWithText("debt 4294967296").assertIsDisplayed()
        compose.onNodeWithText("Home").assertDoesNotExist()
        pressBack()
        compose.onNodeWithText("home debts").assertIsDisplayed()
        pressBack()
        compose.onNodeWithText("home root").assertIsDisplayed()
        compose.onNodeWithText("Home").assertIsSelected()
    }

    @Test
    fun switchingTabsRestoresTheOpenDebtDetail() {
        start()
        compose.onNodeWithText("open debts").performClick()
        compose.onNodeWithText("open debt").performClick()

        compose.runOnUiThread { nav.switchTab(WalletTab.TRACKING) }
        compose.waitForIdle()
        compose.onNodeWithText("tracking root").assertIsDisplayed()
        compose.runOnUiThread { nav.switchTab(WalletTab.HOME) }
        compose.waitForIdle()

        compose.onNodeWithText("debt 4294967296").assertIsDisplayed()
        pressBack()
        compose.onNodeWithText("home debts").assertIsDisplayed()
    }
}
