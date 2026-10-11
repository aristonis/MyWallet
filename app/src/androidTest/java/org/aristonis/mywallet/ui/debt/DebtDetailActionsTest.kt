package org.aristonis.mywallet.ui.debt

import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.remember
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.aristonis.mywallet.domain.model.Debt
import org.aristonis.mywallet.domain.model.DebtDetail
import org.aristonis.mywallet.domain.model.DebtSide
import org.aristonis.mywallet.domain.model.DebtState
import org.aristonis.mywallet.domain.model.DebtSummary
import org.aristonis.mywallet.domain.model.Money
import org.aristonis.mywallet.ui.theme.MyWalletTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.time.LocalDate

@RunWith(AndroidJUnit4::class)
class DebtDetailActionsTest {
    @get:Rule val compose = createComposeRule()
    private val debt = Debt(1, "Ali", DebtSide.I_OWE, "USD")
    private fun state(amount: String, submitting: Boolean = false) = DebtDetailUiState(
        detail = DebtDetail(DebtSummary(debt, Money.of(amount, "USD"),
            if (amount == "0") DebtState.SETTLED else DebtState.OPEN), emptyList()),
        counterpartyInput = "Ali", remainingDisplay = "$amount.00 USD", isLoading = false, isSubmitting = submitting)

    @Test fun anOpenDebtOffersWriteOffThroughItsActualActionCallback() {
        var opened = 0
        show(state("100")) { opened++ }
        compose.reachDebtRow("Write off the rest")
        compose.onNodeWithText("Write off the rest").assertIsDisplayed().performClick()
        compose.runOnIdle { assertEquals(1, opened) }
    }

    @Test fun aSettledDebtHasNoWriteOffAction() {
        show(state("0")) {}
        compose.onNodeWithText("Write off the rest").assertDoesNotExist()
    }

    @Test fun aNegativeDebtHasNoWriteOffAction() {
        show(state("-5")) {}
        compose.onNodeWithText("Write off the rest").assertDoesNotExist()
    }

    @Test fun submittingDisablesWriteOffUntilTheCurrentActionFinishes() {
        show(state("100", submitting = true)) {}
        compose.reachDebtRow("Write off the rest")
        compose.onNodeWithText("Write off the rest").assertIsNotEnabled()
    }

    private fun show(current: DebtDetailUiState, writeOff: () -> Unit) {
        compose.setContent {
            MyWalletTheme(dynamicColor = false) {
                DebtDetailContent(current, onDone = {}, onEditEntry = {}, onCounterpartyChange = {},
                    onRename = {}, onDueDateChange = {}, onNoteChange = {}, onSaveDetails = {}, onRetry = {},
                    today = { LocalDate.of(2026, 10, 12) }, onWriteOff = writeOff, onDeleteDebt = {},
                    snackbar = remember { SnackbarHostState() })
            }
        }
    }
}
