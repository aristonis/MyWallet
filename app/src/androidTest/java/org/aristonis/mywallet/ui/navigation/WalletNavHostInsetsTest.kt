package org.aristonis.mywallet.ui.navigation

import androidx.activity.ComponentActivity
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.isSelectable
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.Dp
import androidx.navigation.NavHostController
import androidx.navigation.compose.composable
import androidx.navigation.compose.navigation
import androidx.navigation.compose.rememberNavController
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.aristonis.mywallet.ui.theme.MyWalletTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The app draws behind the system bars, so every screen pads itself clear of them. The bottom bar
 * already sits above the system navigation bar; a tab that pads for that bar again leaves an empty
 * strip between its content and the bottom bar. A screen with no bottom bar must still clear it.
 */
@RunWith(AndroidJUnit4::class)
class WalletNavHostInsetsTest {

    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private lateinit var nav: NavHostController
    private var navigationBarHeight = Dp.Unspecified

    /** A screen built the way the real ones are: its own Scaffold with the default insets. */
    @Composable
    private fun ScreenWithOwnScaffold() {
        Scaffold { innerPadding ->
            Box(Modifier.padding(innerPadding).fillMaxSize().testTag(CONTENT))
        }
    }

    private fun start() {
        compose.runOnUiThread { compose.activity.enableEdgeToEdge() }
        compose.setContent {
            val density = LocalDensity.current
            navigationBarHeight = with(density) { WindowInsets.navigationBars.getBottom(density).toDp() }
            MyWalletTheme(dynamicColor = false) {
                nav = rememberNavController()
                WalletNavHost(navController = nav) { _ ->
                    val tab = WalletTab.HOME
                    navigation(startDestination = tab.rootRoute, route = tab.route) {
                        composable(tab.rootRoute) { ScreenWithOwnScaffold() }
                        composable(tab.child(Leaf.ADD_TRANSACTION)) { ScreenWithOwnScaffold() }
                    }
                }
            }
        }
        compose.waitForIdle()
    }

    @Test
    fun tabContentEndsAtTheBottomBar() {
        start()

        val contentBottom = compose.onNodeWithTag(CONTENT).getUnclippedBoundsInRoot().bottom
        // The bar's items fill its height, so the first one's top is the bar's top.
        val barTop = compose.onAllNodes(isSelectable())[0].getUnclippedBoundsInRoot().top

        // With no system navigation bar there is nothing to add twice, and the check would pass for nothing.
        assertTrue("the device reports no navigation bar, so this proves nothing", navigationBarHeight.value > 0f)
        assertEquals(barTop.value, contentBottom.value, TOLERANCE_DP)
    }

    @Test
    fun aScreenWithoutTheBottomBarStillClearsTheSystemBar() {
        start()
        compose.runOnUiThread { nav.navigate(WalletTab.HOME.child(Leaf.ADD_TRANSACTION)) }
        compose.waitForIdle()

        val contentBottom = compose.onNodeWithTag(CONTENT).getUnclippedBoundsInRoot().bottom
        val screenBottom = compose.onRoot().getUnclippedBoundsInRoot().bottom

        assertTrue("the device reports no navigation bar, so this proves nothing", navigationBarHeight.value > 0f)
        assertEquals((screenBottom - navigationBarHeight).value, contentBottom.value, TOLERANCE_DP)
    }

    private companion object {
        const val CONTENT = "tab content"
        const val TOLERANCE_DP = 1f
    }
}
