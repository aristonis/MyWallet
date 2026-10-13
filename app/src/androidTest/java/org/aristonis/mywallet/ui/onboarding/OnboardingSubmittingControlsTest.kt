package org.aristonis.mywallet.ui.onboarding

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.aristonis.mywallet.R
import org.aristonis.mywallet.domain.model.Currency
import org.aristonis.mywallet.ui.theme.MyWalletTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class OnboardingSubmittingControlsTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun submissionDisablesFieldsAndPickerAndFailureEnablesThemAgain() {
        var state by mutableStateOf(
            OnboardingUiState(
                currencies = listOf(Currency("USD", "$", 2)),
                selectedCurrencyCode = "USD",
                accountName = "Main",
                openingBalanceInput = "42",
            ),
        )
        compose.setContent {
            MyWalletTheme(dynamicColor = false) {
                OnboardingContent(state, {}, {}, {}, {}, {})
            }
        }
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val currency = context.getString(R.string.account_with_currency, "USD", "$")
        val accountType = context.getString(R.string.account_type_cash)

        compose.onNodeWithText(currency).assertIsEnabled()
        compose.onNodeWithText(accountType).assertIsEnabled()
        compose.onNodeWithText("Main").assertIsEnabled()
        compose.onNodeWithText("42").assertIsEnabled()

        compose.runOnIdle { state = state.copy(isSubmitting = true) }
        compose.onNodeWithText(currency).assertIsNotEnabled()
        compose.onNodeWithText(accountType).assertIsNotEnabled()
        compose.onNodeWithText("Main").assertIsNotEnabled()
        compose.onNodeWithText("42").assertIsNotEnabled()

        compose.runOnIdle { state = state.copy(isSubmitting = false) }
        compose.onNodeWithText(currency).assertIsEnabled()
        compose.onNodeWithText(accountType).assertIsEnabled()
        compose.onNodeWithText("Main").assertIsEnabled()
        compose.onNodeWithText("42").assertIsEnabled()
    }

    @Test
    fun pickerOpenAtSubmissionDoesNotReopenAfterFailure() {
        var state by mutableStateOf(
            OnboardingUiState(
                currencies = listOf(Currency("USD", "$", 2)),
                selectedCurrencyCode = "USD",
                accountName = "Main",
            ),
        )
        compose.setContent {
            MyWalletTheme(dynamicColor = false) {
                OnboardingContent(state, {}, {}, {}, {}, {})
            }
        }
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val currency = context.getString(R.string.account_with_currency, "USD", "$")
        val search = context.getString(R.string.action_search)

        compose.onNodeWithText(currency).performClick()
        compose.onNodeWithText(search).assertIsDisplayed()
        compose.runOnIdle { state = state.copy(isSubmitting = true) }
        compose.onNodeWithText(search).assertDoesNotExist()
        compose.runOnIdle { state = state.copy(isSubmitting = false) }
        compose.onNodeWithText(search).assertDoesNotExist()
    }
}
