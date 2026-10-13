package org.aristonis.mywallet.ui.reports

import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import org.aristonis.mywallet.ui.theme.MyWalletTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

/** The overflow offers an explicit CSV action and cannot start it while export is unavailable. */
class TrackingExportMenuTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun exportCsvFromTheOverflowStartsOneRequest() {
        var requests = 0
        compose.setContent {
            MyWalletTheme(dynamicColor = false) { TrackingExportMenu(enabled = true, onExport = { requests++ }) }
        }
        compose.onNodeWithContentDescription("More options").performClick()
        compose.onNodeWithText("Export these dates (CSV)").performClick()
        compose.runOnIdle { assertEquals(1, requests) }
    }

    @Test
    fun unavailableExportDisablesTheOverflow() {
        compose.setContent {
            MyWalletTheme(dynamicColor = false) { TrackingExportMenu(enabled = false, onExport = {}) }
        }
        compose.onNodeWithContentDescription("More options").assertIsNotEnabled()
    }
}
