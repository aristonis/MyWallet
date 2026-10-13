package org.aristonis.mywallet.ui.navigation

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.isSelectable
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.aristonis.mywallet.ui.theme.MyWalletTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
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

    @Test
    fun labelsDoNotSplitAcrossLinesAtTwoHundredPercentInRtl() {
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(
                LocalLayoutDirection provides LayoutDirection.Rtl,
                LocalDensity provides Density(density.density, 2f),
            ) {
                MyWalletTheme(dynamicColor = false) {
                    WalletNavigationBar(selected = WalletTab.HOME, onSelect = {})
                }
            }
        }

        listOf("Home", "Transactions", "Tracking", "Settings").forEach { label ->
            val layouts = mutableListOf<TextLayoutResult>()
            compose.onNodeWithText(label, useUnmergedTree = true).assertIsDisplayed()
                .fetchSemanticsNode().config[SemanticsActions.GetTextLayoutResult].action?.invoke(layouts)
            val layout = layouts.single()
            assertEquals("$label must not split across lines", 1, layout.lineCount)
            assertFalse(
                "$label must remain fully visible: size=${layout.size}, " +
                    "overflowWidth=${layout.didOverflowWidth}, overflowHeight=${layout.didOverflowHeight}",
                layout.hasVisualOverflow,
            )
        }
    }

    @Test
    fun largeTextRtlTabsStaySelectableAndTouchSized() {
        var requested: WalletTab? = null
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(
                LocalLayoutDirection provides LayoutDirection.Rtl,
                LocalDensity provides Density(density.density, 2f),
            ) {
                MyWalletTheme(dynamicColor = false) {
                    WalletNavigationBar(selected = WalletTab.SETTINGS, onSelect = { requested = it })
                }
            }
        }

        compose.onNodeWithText("Settings").assertIsSelected()
        val tabs = compose.onAllNodes(isSelectable())
        assertEquals(4, tabs.fetchSemanticsNodes().size)
        repeat(4) { index ->
            val tab = tabs[index]
            assertEquals(Role.Tab, tab.fetchSemanticsNode().config[SemanticsProperties.Role])
            val bounds = tab.getUnclippedBoundsInRoot()
            assertTrue("Tab width must be at least 48dp", bounds.right - bounds.left >= 48.dp)
            assertTrue("Tab height must be at least 48dp", bounds.bottom - bounds.top >= 48.dp)
        }

        compose.onNodeWithText("Tracking").performClick()
        assertEquals(WalletTab.TRACKING, requested)
    }
}
