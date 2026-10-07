package org.aristonis.mywallet.ui.navigation

import androidx.activity.ComponentActivity
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
        Text(name)
        pushes.forEach { (label, route) ->
            Button(onClick = { nav.navigate(route) }) { Text(label) }
        }
    }

    private fun NavGraphBuilder.stubTab(tab: WalletTab, extras: NavGraphBuilder.() -> Unit = {}) {
        navigation(startDestination = tab.rootRoute, route = tab.route) {
            composable(tab.rootRoute) {
                Stub(
                    name = "${tab.route} root",
                    pushes = listOf("open rates from ${tab.route}" to tab.child(Leaf.RATES)),
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
                    stubTab(WalletTab.HOME)
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

    /**
     * A supporting screen keeps the bar. Without it the tabs would be unreachable the moment you
     * opened anything, and a tab could never be left part-way through — so its saved stack would
     * never exist to be restored.
     */
    @Test
    fun theNavigationBarStaysOnASupportingScreen() {
        start()

        compose.onNodeWithText("open rates from home").performClick()

        compose.onNodeWithText("home rates").assertIsDisplayed()
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

    /**
     * The reason each tab is its own graph. Leaving a tab part-way through a flow and coming back has
     * to land where the user was, not restart them at the top.
     */
    @Test
    fun switchingTabsRestoresTheStackLeftBehind() {
        start()

        compose.onNodeWithText("open rates from home").performClick()
        compose.onNodeWithText("home rates").assertIsDisplayed()

        compose.onNodeWithText("Tracking").performClick()
        compose.onNodeWithText("tracking root").assertIsDisplayed()

        compose.onNodeWithText("Home").performClick()

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
}
