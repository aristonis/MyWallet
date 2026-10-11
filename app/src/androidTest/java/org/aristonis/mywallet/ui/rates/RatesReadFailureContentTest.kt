package org.aristonis.mywallet.ui.rates

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.aristonis.mywallet.R
import org.aristonis.mywallet.ui.theme.MyWalletTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RatesReadFailureContentTest {
    @get:Rule val compose = createComposeRule()

    @Test fun anUnreadableWalletOffersRetryInsteadOfSayingRatesAreNotNeeded() {
        var retries = 0
        compose.setContent {
            MyWalletTheme(dynamicColor = false) {
                ManageRatesContent(ManageRatesUiState(isLoading = false, loadFailed = true),
                    onInputChanged = { _, _ -> }, onSave = {}, onDone = {}, onRetry = { retries++ })
            }
        }
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        compose.onNodeWithText(context.getString(R.string.rates_data_unreadable)).assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.action_retry)).performClick()
        compose.onNodeWithText(context.getString(R.string.rates_not_needed)).assertDoesNotExist()
        compose.runOnIdle { assertEquals(1, retries) }
    }
}
