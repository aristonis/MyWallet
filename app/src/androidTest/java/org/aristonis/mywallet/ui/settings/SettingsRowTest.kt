package org.aristonis.mywallet.ui.settings

import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.aristonis.mywallet.ui.theme.MyWalletTheme
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The point of the settings list is that a row answers its own question. If the current value stops
 * showing, the screen quietly turns back into the page of unlabelled buttons it replaced.
 */
@RunWith(AndroidJUnit4::class)
class SettingsRowTest {

    @get:Rule val compose = createComposeRule()

    @Test
    fun aRowStatesWhatItIsCurrentlySetTo() {
        compose.setContent {
            MyWalletTheme(dynamicColor = false) {
                SettingsRow(label = "Base currency", value = "USD", onClick = {})
            }
        }

        compose.onNodeWithText("Base currency").assertIsDisplayed()
        compose.onNodeWithText("USD").assertIsDisplayed()
    }

    @Test
    fun aRowOpensWhatItPointsAt() {
        var opened = false
        compose.setContent {
            MyWalletTheme(dynamicColor = false) {
                SettingsRow(label = "Categories", onClick = { opened = true })
            }
        }

        compose.onNodeWithText("Categories").assertHasClickAction().performClick()

        assertTrue(opened)
    }

    /** A row with nothing set yet shows the label alone rather than an empty-looking value. */
    @Test
    fun aRowWithNoValueStillReads() {
        compose.setContent {
            MyWalletTheme(dynamicColor = false) {
                SettingsRow(label = "Accounts", value = null, onClick = {})
            }
        }

        compose.onNodeWithText("Accounts").assertIsDisplayed()
    }
}
