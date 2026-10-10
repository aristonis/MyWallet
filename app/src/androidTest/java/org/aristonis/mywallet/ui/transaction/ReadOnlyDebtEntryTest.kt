package org.aristonis.mywallet.ui.transaction

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.aristonis.mywallet.domain.model.DebtSide
import org.aristonis.mywallet.domain.model.Money
import org.aristonis.mywallet.domain.model.Transaction
import org.aristonis.mywallet.ui.message.UiMessage
import org.aristonis.mywallet.ui.theme.MyWalletTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.time.LocalDate

/**
 * Rendering and confirmation of the read-only debt entry. Whether a delete is refused is decided in
 * the domain and mapped by the view model (see EditDebtKindReadOnlyTest); here the refusal is only
 * given to the screen as state, to check it is shown.
 */
@RunWith(AndroidJUnit4::class)
class ReadOnlyDebtEntryTest {
    @get:Rule val compose = createComposeRule()
    private val entry = Transaction.WriteOff(
        id = 2, side = DebtSide.OWED_TO_ME, debtId = 8,
        amount = Money.of("100", "USD"), categoryId = 6, date = LocalDate.of(2026, 10, 10),
    )

    @Test fun aDebtWithoutEditFieldsStillOffersGuardedDeletionAfterConfirmation() {
        var deletes = 0
        compose.setContent {
            MyWalletTheme(dynamicColor = false) {
                ReadOnlyEntry(EditTransactionUiState(loaded = entry, readOnly = true, amountInput = "100", note = "Unpaid balance"), entry, onDelete = { deletes++ })
            }
        }
        compose.onNodeWithText("Save transaction").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithText("Delete").performScrollTo().assertIsEnabled()
        compose.onNodeWithText("Delete").performClick()
        compose.onNodeWithText("Delete this transaction?").assertIsDisplayed()
        compose.runOnIdle { assertEquals(0, deletes) }
        compose.onNode(hasText("Delete") and hasAnyAncestor(isDialog())).performClick()
        compose.runOnIdle { assertEquals(1, deletes) }
    }

    @Test fun deletingIsDisabledWhileAWritingActionRuns() {
        compose.setContent {
            MyWalletTheme(dynamicColor = false) {
                ReadOnlyEntry(EditTransactionUiState(loaded = entry, readOnly = true, amountInput = "100", note = "Unpaid balance", isSubmitting = true), entry, onDelete = {})
            }
        }
        compose.onNodeWithText("Delete").performScrollTo().assertIsNotEnabled()
    }

    @Test fun aRefusalShownByTheStateNamesThePersonWithoutScrolling() {
        val state = mutableStateOf(EditTransactionUiState(loaded = entry, readOnly = true, amountInput = "100", note = "Unpaid balance"))
        compose.setContent {
            MyWalletTheme(dynamicColor = false) {
                ReadOnlyEntry(state.value, entry, onDelete = {
                    state.value = state.value.copy(error = UiMessage.DebtBelowZero("Sara", Money.of("5", "USD")))
                })
            }
        }
        compose.onNodeWithText("Delete").performScrollTo().performClick()
        compose.onNode(hasText("Delete") and hasAnyAncestor(isDialog())).performClick()
        compose.onNode(hasText("That would take", substring = true) and hasText("Sara", substring = true)).assertIsDisplayed()
    }
}
