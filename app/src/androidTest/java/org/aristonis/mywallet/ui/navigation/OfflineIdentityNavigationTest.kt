package org.aristonis.mywallet.ui.navigation

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.navigation.NavHostController
import androidx.navigation.compose.rememberNavController
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.aristonis.mywallet.ui.theme.MyWalletTheme
import org.junit.Rule
import org.junit.Test
import org.junit.Assert.assertEquals
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class OfflineIdentityNavigationTest {
    @get:Rule val compose = createAndroidComposeRule<IdentityNavigationHostActivity>()
    private lateinit var nav: NavHostController

    @Test
    fun settingsOpensPrivacyAndSupportAndBackReturnsToSettings() {
        compose.setContent {
            MyWalletTheme(dynamicColor = false) {
                nav = rememberNavController()
                WalletNavHost(navController = nav)
            }
        }

        compose.onNodeWithText("Settings").performClick()
        compose.onNodeWithText("Privacy information").performScrollTo().performClick()
        compose.onNodeWithText("Records on your device").assertIsDisplayed()
        pressBack()
        assertSettingsRoot()

        compose.onNodeWithText("Support and FAQ").performScrollTo().performClick()
        compose.onNodeWithText("Getting started").assertIsDisplayed()
        pressBack()
        assertSettingsRoot()
    }

    private fun pressBack() {
        compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }
        compose.waitForIdle()
    }

    private fun assertSettingsRoot() {
        compose.runOnUiThread {
            assertEquals(WalletTab.SETTINGS.rootRoute, nav.currentDestination?.route)
        }
    }
}
