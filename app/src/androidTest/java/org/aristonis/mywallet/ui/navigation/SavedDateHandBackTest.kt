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
import androidx.navigation.compose.rememberNavController
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.aristonis.mywallet.ui.theme.MyWalletTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.time.LocalDate

/**
 * An editor that saves hands the entry's date back to the list it was opened from, through the
 * list's own back-stack entry. The list hears it exactly once, and not at all when nothing was saved.
 */
@RunWith(AndroidJUnit4::class)
class SavedDateHandBackTest {

    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private lateinit var nav: NavHostController
    private val heard = mutableListOf<LocalDate>()
    private val saved = LocalDate.of(2026, 8, 30)

    private fun start() {
        compose.setContent {
            MyWalletTheme(dynamicColor = false) {
                nav = rememberNavController()
                NavHost(navController = nav, startDestination = LIST) {
                    composable(LIST) { entry ->
                        SavedDateEffect(entry) { heard += it }
                        Button(onClick = { nav.navigate(EDITOR) }) { Text("open editor") }
                    }
                    composable(EDITOR) {
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
                NavHost(navController = nav, startDestination = LIST) {
                    composable(LIST) { entry ->
                        SavedDateEffect(entry) { heard += it }
                        Button(onClick = { nav.navigate(EDITOR) }) { Text("open editor") }
                    }
                    dialog(EDITOR) {
                        Button(onClick = { nav.returnSavedDate(if (heard.isEmpty()) saved else second) }) { Text("save") }
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
    fun cancellingHandsNothingBack() {
        start()
        compose.onNodeWithText("open editor").performClick()
        compose.onNodeWithText("cancel").performClick()
        compose.waitForIdle()

        assertEquals(emptyList<LocalDate>(), heard)
    }

    private companion object {
        const val LIST = "list"
        const val EDITOR = "editor"
    }
}
