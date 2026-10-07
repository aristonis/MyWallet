package org.aristonis.mywallet.ui.navigation

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.aristonis.mywallet.ui.theme.MyWalletTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The bar is how the four destinations are reached, so what matters is that all four are actually
 * offered, that the one you are on is the one shown as selected, and that a tap asks for the tab the
 * user pointed at rather than its neighbour.
 */
@RunWith(AndroidJUnit4::class)
class WalletNavigationBarTest {

    @get:Rule val compose = createComposeRule()

    private fun setBar(selected: WalletTab?, onSelect: (WalletTab) -> Unit = {}) {
        compose.setContent {
            MyWalletTheme(dynamicColor = false) {
                WalletNavigationBar(selected = selected, onSelect = onSelect)
            }
        }
    }

    @Test
    fun allFourDestinationsAreOffered() {
        setBar(selected = WalletTab.HOME)

        compose.onNodeWithText("Home").assertIsDisplayed()
        compose.onNodeWithText("Transactions").assertIsDisplayed()
        compose.onNodeWithText("Tracking").assertIsDisplayed()
        compose.onNodeWithText("Settings").assertIsDisplayed()
    }

    @Test
    fun onlyTheCurrentTabReadsAsSelected() {
        setBar(selected = WalletTab.TRACKING)

        compose.onNodeWithText("Tracking").assertIsSelected()
        compose.onNodeWithText("Home").assertIsNotSelected()
        compose.onNodeWithText("Transactions").assertIsNotSelected()
        compose.onNodeWithText("Settings").assertIsNotSelected()
    }

    @Test
    fun tappingATabAsksForThatTab() {
        var requested: WalletTab? = null
        setBar(selected = WalletTab.HOME, onSelect = { requested = it })

        compose.onNodeWithText("Transactions").performClick()

        assertEquals(WalletTab.TRANSACTIONS, requested)
    }

    /**
     * Before the first destination resolves there is no selected tab. The bar still has to render —
     * blanking it would make the app briefly unnavigable.
     */
    @Test
    fun theBarRendersBeforeATabIsResolved() {
        setBar(selected = null)

        compose.onNodeWithText("Home").assertIsDisplayed()
        compose.onNodeWithText("Home").assertIsNotSelected()
    }
}
