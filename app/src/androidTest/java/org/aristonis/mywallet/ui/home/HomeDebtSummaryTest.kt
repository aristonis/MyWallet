package org.aristonis.mywallet.ui.home

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.aristonis.mywallet.domain.model.Account
import org.aristonis.mywallet.domain.model.CreditStatus
import org.aristonis.mywallet.domain.model.Money
import org.aristonis.mywallet.ui.theme.MyWalletTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class HomeDebtSummaryTest {
    @get:Rule val compose = createComposeRule()
    private fun show(state: HomeUiState, onDebts: () -> Unit = {}, onManageRates: () -> Unit = {}) {
        compose.setContent {
            MyWalletTheme(dynamicColor = false) {
                HomeContent(state, onAddTransaction = {}, onAddAccount = {}, onManageAccounts = {},
                    onManageRates = onManageRates, onSettings = {}, onDebts = onDebts, onRetry = {})
            }
        }
    }

    @Test fun balanceIsTheHeroAndBothOwedRowsOpenDebts() {
        var openings = 0
        show(HomeUiState(summary = HomeSummaryState.Amounts("700.00 USD", "300.00 USD", "1,450.00 USD", "-450.00 USD"),
            isLoading = false), onDebts = { openings++ })
        compose.onNodeWithText("Balance").assertIsDisplayed()
        compose.onNodeWithText("-450.00 USD").assertIsDisplayed()
        compose.onNodeWithText("In my pocket").assertIsDisplayed()
        compose.onNodeWithText("700.00 USD").assertIsDisplayed()
        compose.onNode(hasText("Owed to me") and hasClickAction()).performClick()
        compose.onNode(hasText("I owe") and hasClickAction()).performClick()
        compose.runOnIdle { assertEquals(2, openings) }
    }

    @Test fun negativeDebtWarningRemainsActionableWhenTheTotalNeedsAnExchangeRate() {
        var debts = 0
        var rates = 0
        show(HomeUiState(summary = HomeSummaryState.MissingRate("EUR"), debtsBelowZero = listOf(1), isLoading = false),
            onDebts = { debts++ }, onManageRates = { rates++ })
        compose.onNode(hasText("EUR", substring = true) and hasClickAction()).performClick()
        compose.onNode(hasText("below zero", substring = true) and hasClickAction()).performScrollTo().assertIsDisplayed().performClick()
        compose.runOnIdle {
            assertEquals(1, debts)
            assertEquals(1, rates)
        }
    }

    @Test fun overLimitCreditShowsAvailableAmountAndAWarningIcon() {
        val account = Account(1, "Credit account", "credit_card", "USD", Money.of("-120", "USD"), creditLimit = Money.of("100", "USD"))
        val row = AccountRow(account, Money.of("-120", "USD"), Money.of("-120", "USD"), "-120.00 USD", "-120.00 USD",
            credit = CreditStatus.of(Money.of("-120", "USD"), account.creditLimit), owedDisplay = "120.00 USD", availableDisplay = "0.00 USD",
            overLimitDisplay = "20.00 USD")
        show(HomeUiState(summary = HomeSummaryState.Amounts("0.00 USD", "0.00 USD", "120.00 USD", "-120.00 USD"),
            accounts = listOf(row), isLoading = false))
        compose.onNode(hasText("Over limit by", substring = true) and hasText("20.00 USD", substring = true)).performScrollTo().assertIsDisplayed()
        compose.onNode(hasContentDescription("Warning")).assertIsDisplayed()
        compose.onNode(hasText("Available credit:", substring = true) and hasText("0.00 USD", substring = true)).assertIsDisplayed()
    }
}
