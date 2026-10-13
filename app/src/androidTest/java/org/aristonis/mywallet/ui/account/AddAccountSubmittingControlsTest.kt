package org.aristonis.mywallet.ui.account

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.aristonis.mywallet.R
import org.aristonis.mywallet.domain.model.AccountTypeRegistry
import org.aristonis.mywallet.domain.model.Currency
import org.aristonis.mywallet.domain.model.FeeMode
import org.aristonis.mywallet.ui.theme.MyWalletTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AddAccountSubmittingControlsTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun submissionDisablesFieldsAndPickersAndFailureEnablesThemAgain() {
        var state by mutableStateOf(
            AddAccountUiState(
                currencies = listOf(Currency("USD", "$", 2)),
                selectedCurrencyCode = "USD",
                name = "Main",
                accountTypeKey = AccountTypeRegistry.BuiltIns.CREDIT_CARD.key,
                openingBalanceInput = "42",
                creditLimitInput = "77",
                feeMode = FeeMode.PERCENT,
                feeValueInput = "3",
            ),
        )
        compose.setContent {
            MyWalletTheme(dynamicColor = false) {
                AddAccountContent(state, {}, {}, {}, {}, {}, {}, {}, {}, {})
            }
        }
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val currency = context.getString(R.string.account_with_currency, "USD", "$")
        val accountType = context.getString(R.string.account_type_credit_card)
        val feeMode = context.getString(R.string.fee_percent)

        compose.onNodeWithText(currency).assertIsEnabled()
        compose.onNodeWithText(accountType).assertIsEnabled()
        compose.onNodeWithText("Main").assertIsEnabled()
        compose.onNodeWithText("42").assertIsEnabled()
        compose.onNodeWithText("77").assertIsEnabled()
        compose.onNodeWithText(feeMode).assertIsEnabled()
        compose.onNodeWithText("3").assertIsEnabled()

        compose.runOnIdle { state = state.copy(isSubmitting = true) }
        compose.onNodeWithText(currency).assertIsNotEnabled()
        compose.onNodeWithText(accountType).assertIsNotEnabled()
        compose.onNodeWithText("Main").assertIsNotEnabled()
        compose.onNodeWithText("42").assertIsNotEnabled()
        compose.onNodeWithText("77").assertIsNotEnabled()
        compose.onNodeWithText(feeMode).assertIsNotEnabled()
        compose.onNodeWithText("3").assertIsNotEnabled()

        compose.runOnIdle { state = state.copy(isSubmitting = false) }
        compose.onNodeWithText(currency).assertIsEnabled()
        compose.onNodeWithText(accountType).assertIsEnabled()
        compose.onNodeWithText("Main").assertIsEnabled()
        compose.onNodeWithText("42").assertIsEnabled()
        compose.onNodeWithText("77").assertIsEnabled()
        compose.onNodeWithText(feeMode).assertIsEnabled()
        compose.onNodeWithText("3").assertIsEnabled()
    }
}
