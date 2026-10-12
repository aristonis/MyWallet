package org.aristonis.mywallet.ui.account

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.aristonis.mywallet.R
import org.aristonis.mywallet.domain.model.FeeMode
import org.aristonis.mywallet.ui.format.bidiIsolate
import org.aristonis.mywallet.ui.theme.MyWalletTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Fee mode choices and units remain readable and editable in the account form. */
@RunWith(AndroidJUnit4::class)
class AccountFeeEditorTest {
    @get:Rule val compose = createComposeRule()

    private fun text(id: Int) = InstrumentationRegistry.getInstrumentation().targetContext.getString(id)

    @Test
    fun selectingModesShowsTheCorrectUnitsAndNoneHidesTheInput() {
        var mode by mutableStateOf<FeeMode?>(null)
        var input by mutableStateOf("")
        compose.setContent {
            MyWalletTheme(dynamicColor = false) {
                AccountFeeEditor(mode, input, "EUR", { mode = it }, { input = it })
            }
        }
        compose.onNode(hasSetTextAction()).assertDoesNotExist()
        compose.onNodeWithText(text(R.string.fee_none)).performClick()
        compose.onNodeWithText(text(R.string.fee_percent)).performClick()
        compose.onNodeWithText(text(R.string.fee_percent_value)).assertIsDisplayed()
        compose.onNode(hasSetTextAction()).performTextInput("2.125")
        compose.onNodeWithText("%", useUnmergedTree = true).assertIsDisplayed()
        compose.runOnIdle { assertEquals("2.125", input) }
        compose.onNodeWithText(text(R.string.fee_percent)).performClick()
        compose.onNodeWithText(text(R.string.fee_fixed)).performClick()
        compose.onNodeWithText(text(R.string.fee_fixed_value)).assertIsDisplayed()
        compose.onNodeWithText(bidiIsolate("EUR"), useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithText(text(R.string.fee_fixed)).performClick()
        compose.onNodeWithText(text(R.string.fee_none)).performClick()
        compose.onNode(hasSetTextAction()).assertDoesNotExist()
    }

    @Test
    fun fixedFeeCurrencyIsReadableInDarkRtlAtTwoHundredPercentFont() {
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(
                LocalLayoutDirection provides LayoutDirection.Rtl,
                LocalDensity provides Density(density.density, 2f),
            ) {
                MyWalletTheme(darkTheme = true, dynamicColor = false) {
                    AccountFeeEditor(FeeMode.FIXED, "3.50", "USD", {}, {})
                }
            }
        }
        compose.onNodeWithText(text(R.string.account_default_fee)).assertIsDisplayed()
        compose.onNodeWithText(text(R.string.fee_fixed_value)).assertIsDisplayed()
        compose.onNodeWithText(bidiIsolate("USD"), useUnmergedTree = true).assertIsDisplayed()
        compose.onNode(hasSetTextAction()).assertIsDisplayed()
    }
}
