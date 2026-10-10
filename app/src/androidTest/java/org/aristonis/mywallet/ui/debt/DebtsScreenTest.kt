package org.aristonis.mywallet.ui.debt

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.aristonis.mywallet.domain.model.Debt
import org.aristonis.mywallet.domain.model.DebtSide
import org.aristonis.mywallet.domain.model.DebtState
import org.aristonis.mywallet.domain.model.DebtSummary
import org.aristonis.mywallet.domain.model.DebtTotals
import org.aristonis.mywallet.domain.model.Money
import org.aristonis.mywallet.ui.theme.MyWalletTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.time.LocalDate

@RunWith(AndroidJUnit4::class)
class DebtsScreenTest {
    @get:Rule val compose = createComposeRule()
    private fun row(id: Long, name: String, amount: String = "10", state: DebtState = DebtState.OPEN): DebtRow =
        DebtRow(DebtSummary(Debt(id, name, DebtSide.I_OWE, "USD"), Money.of(amount, "USD"), state), "$amount.00 USD")

    @Test fun overdueAndSettledRowsAreReadableAndOpenTheCorrectDebt() {
        var opened: Long? = null
        compose.setContent {
            MyWalletTheme(dynamicColor = false) {
                DebtsContent(DebtsUiState(open = listOf(row(1, "Ali", state = DebtState.OVERDUE)),
                    settled = listOf(row(2, "Sara", "0", DebtState.SETTLED)), isLoading = false),
                    onDone = {}, onOpenDebt = { opened = it }, onManageRates = {}, onSelectSide = {}, onRetry = {})
            }
        }
        compose.onNodeWithText("Overdue").assertIsDisplayed()
        compose.onNodeWithText("Ali").performClick()
        compose.runOnIdle { assertEquals(1L, opened) }
        compose.reachDebtRow("Sara")
        compose.onAllNodesWithText("Settled")[0].assertIsDisplayed()
        compose.onNodeWithText("Sara").performClick()
        compose.runOnIdle { assertEquals(2L, opened) }
        compose.debtScreenshot("debts-overdue-settled")
    }

    @Test fun missingRateKeepsNativeRowsAndOffersManageRates() {
        var managed = false
        compose.setContent {
            MyWalletTheme(dynamicColor = false) {
                DebtsContent(DebtsUiState(open = listOf(row(1, "Ali")), totals = DebtTotals.MissingRate("EUR"), isLoading = false),
                    onDone = {}, onOpenDebt = {}, onManageRates = { managed = true }, onSelectSide = {}, onRetry = {})
            }
        }
        compose.onNodeWithText("Ali").assertIsDisplayed()
        compose.onNodeWithText("10.00 USD").assertIsDisplayed()
        compose.onNodeWithText("EUR", substring = true).assertIsDisplayed()
        compose.onNodeWithText("Manage rates").performClick()
        compose.runOnIdle { assertEquals(true, managed) }
        compose.debtScreenshot("debts-missing-rate")
    }

    @Test fun sideSegmentAndRetryInvokeTheirActions() {
        var selected: DebtSide? = null
        var retried = false
        var state by mutableStateOf(DebtsUiState(isLoading = false))
        compose.setContent {
            MyWalletTheme(dynamicColor = false) {
                DebtsContent(state, onDone = {}, onOpenDebt = {}, onManageRates = {},
                    onSelectSide = { selected = it }, onRetry = { retried = true })
            }
        }
        compose.onNode(hasText("Owed to me") and hasClickAction()).performClick()
        compose.runOnIdle {
            assertEquals(DebtSide.OWED_TO_ME, selected)
            state = state.copy(loadFailed = true)
        }
        compose.onNodeWithText("Retry").assertIsDisplayed().performClick()
        compose.runOnIdle { assertEquals(true, retried) }
        compose.debtScreenshot("debts-read-failure")
    }

    @Test fun lastNegativeDebtIsReachableInDarkRtlAtTwoHundredPercentFont() {
        var opened: Long? = null
        val rows = (1L..30L).map { row(it, "Person $it", if (it == 30L) "-3" else "10") }
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl,
                LocalDensity provides Density(density.density, 2f)) {
                MyWalletTheme(darkTheme = true, dynamicColor = false) {
                    DebtsContent(DebtsUiState(open = rows, isLoading = false), onDone = {}, onOpenDebt = { opened = it },
                        onManageRates = {}, onSelectSide = {}, onRetry = {})
                }
            }
        }
        compose.reachDebtRow("Person 30")
        compose.onNodeWithText("Person 30").assertIsDisplayed().performClick()
        compose.runOnIdle { assertEquals(30L, opened) }
        compose.debtScreenshot("debts-dark-rtl-font200-final-row")
    }
}
