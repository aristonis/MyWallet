package org.aristonis.mywallet.ui.debt

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
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
class RecordRepaymentScreenTest {
    @get:Rule val compose = createComposeRule()
    private val debt = Debt(1, "Ali", DebtSide.I_OWE, "USD")
    private val loaded = RecordRepaymentUiState(
        debt = DebtDetail(DebtSummary(debt, Money.of("10", "USD"), DebtState.OPEN), emptyList()),
        date = LocalDate.of(2026, 10, 12),
        isLoading = false,
    )

    private fun show(state: RecordRepaymentUiState, onOpenEntry: (Long) -> Unit = {}) {
        compose.setContent {
            MyWalletTheme(dynamicColor = false) {
                RecordRepaymentContent(
                    state = state, onDone = {}, onOpenEntry = onOpenEntry, onAccountSelected = {},
                    onPrincipalChanged = {}, onAccountPrincipalChanged = {}, onInterestChanged = {},
                    onInterestCategorySelected = {}, onPayRest = {}, onRateChanged = { _, _ -> },
                    onDateSelected = {}, onNoteChanged = {}, onSubmit = {},
                )
            }
        }
    }

    @Test fun aNewRepaymentHasNoOpenEntryAndUsesTheRecordWording() {
        show(loaded)

        compose.onNodeWithText("Open entry").assertDoesNotExist()
        compose.onNodeWithText("Edit repayment").assertDoesNotExist()
        compose.onNodeWithText("Save repayment").assertIsDisplayed()
    }

    @Test fun editModeOffersOpenEntryWithTheRepaymentIdAndTheEditWording() {
        var opened: Long? = null
        show(loaded.copy(editingRepaymentId = 7), onOpenEntry = { opened = it })

        compose.onNodeWithText("Edit repayment").assertIsDisplayed()
        compose.onNodeWithText("Save changes").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Open entry").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(7L, opened) }
    }

    @Test fun theRemainingAndAccountMovesLinesAreShown() {
        show(loaded.copy(remainingDisplay = "10.00 USD", accountMovesDisplay = "76.93 EUR"))

        // The amount is bidi-isolated inside its sentence, so match the amount itself.
        compose.onNodeWithText("10.00 USD", substring = true).assertExists()
        compose.onNodeWithText("76.93 EUR", substring = true).assertExists()
    }
}
