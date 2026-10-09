package org.aristonis.mywallet.ui.navigation

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.dialog
import androidx.navigation.compose.navigation
import androidx.navigation.compose.rememberNavController
import androidx.navigation.NavGraphBuilder
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.aristonis.mywallet.ui.theme.MyWalletTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.time.LocalDate

/**
 * An editor that saves in the Transactions graph hands the entry's date back to the list it was opened from, through the
 * list's own back-stack entry. The list hears it exactly once, and not at all when nothing was saved.
 * Opened from anywhere outside the Transactions graph, the editor only pops and writes nothing.
 */
@RunWith(AndroidJUnit4::class)
class SavedDateHandBackTest {

    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private lateinit var nav: NavHostController
    private val heard = mutableListOf<LocalDate>()
    private val saved = LocalDate.of(2026, 8, 30)
    private var tab = WalletTab.TRANSACTIONS

    /** The screens of one tab's graph: its root route is the list, its add route is the editor. */
    private fun NavGraphBuilder.graphOf(tab: WalletTab, screens: NavGraphBuilder.() -> Unit) =
        navigation(startDestination = tab.rootRoute, route = tab.route, builder = screens)

    private fun start() {
        compose.setContent {
            MyWalletTheme(dynamicColor = false) {
                nav = rememberNavController()
                NavHost(navController = nav, startDestination = tab.route) {
                    graphOf(tab) {
                        composable(tab.rootRoute) { entry ->
                            SavedDateEffect(entry) { heard += it }
                            Button(onClick = { nav.navigate(tab.child(Leaf.ADD_TRANSACTION)) }) { Text("open editor") }
                        }
                        composable(tab.child(Leaf.ADD_TRANSACTION)) {
                            // Stacked, not overlapping: a click on one must not land on the other.
                            Column {
                                Button(onClick = { nav.returnSavedDate(saved) }) { Text("save") }
                                Button(onClick = { nav.popBackStack() }) { Text("cancel") }
                            }
                        }
                    }
                }
            }
        }
    }

    @Test
    fun theListHearsTheSavedDateOnce() {
        start()
        compose.onNodeWithText("open editor").performClick()
        compose.onNodeWithText("save").performClick()
        compose.waitForIdle()

        compose.onNodeWithText("open editor").assertIsDisplayed()
        assertEquals(listOf(saved), heard)
        // The entry's saved state is what outlives a rotation or process death; once heard, the date
        // is cleared from it, so a rebuilt list cannot hear it a second time.
        assertNull(checkNotNull(nav.currentBackStackEntry).savedStateHandle.get<Long>(SAVED_DATE_KEY))
    }

    @Test
    fun anEditorThatKeepsTheListOnScreenIsHeardEverySave() {
        // A dialog editor leaves the list composed, so the list must keep listening after the first date.
        val second = LocalDate.of(2026, 9, 2)
        compose.setContent {
            MyWalletTheme(dynamicColor = false) {
                nav = rememberNavController()
                NavHost(navController = nav, startDestination = tab.route) {
                    graphOf(tab) {
                        composable(tab.rootRoute) { entry ->
                            SavedDateEffect(entry) { heard += it }
                            Button(onClick = { nav.navigate(tab.child(Leaf.ADD_TRANSACTION)) }) { Text("open editor") }
                        }
                        dialog(tab.child(Leaf.ADD_TRANSACTION)) {
                            Button(onClick = { nav.returnSavedDate(if (heard.isEmpty()) saved else second) }) { Text("save") }
                        }
                    }
                }
            }
        }

        repeat(2) {
            compose.onNodeWithText("open editor").performClick()
            compose.onNodeWithText("save").performClick()
            compose.waitForIdle()
        }

        assertEquals(listOf(saved, second), heard)
    }

    @Test
    fun outsideTheTransactionsGraphTheEditorOnlyPops() {
        tab = WalletTab.HOME
        start()
        compose.onNodeWithText("open editor").performClick()
        compose.onNodeWithText("save").performClick()
        compose.waitForIdle()

        compose.onNodeWithText("open editor").assertIsDisplayed() // the editor is gone
        assertEquals(emptyList<LocalDate>(), heard)
        val handle = checkNotNull(nav.currentBackStackEntry).savedStateHandle
        // the listener registers the key with no value, so check that no date is waiting in it
        assertNull(handle.get<Long?>(SAVED_DATE_KEY)) // nothing was left behind for a later screen
    }

    @Test
    fun cancellingHandsNothingBack() {
        start()
        compose.onNodeWithText("open editor").performClick()
        compose.onNodeWithText("cancel").performClick()
        compose.waitForIdle()

        assertEquals(emptyList<LocalDate>(), heard)
    }
}
