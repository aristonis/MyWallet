package org.aristonis.mywallet.ui.debt

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.aristonis.mywallet.domain.model.Debt
import org.aristonis.mywallet.domain.model.DebtDeletionPreview
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

@RunWith(AndroidJUnit4::class)
class DeleteDebtDialogTest {
    @get:Rule val compose = createComposeRule()
    private val day = LocalDate.of(2026, 10, 12)
    private val debt = Debt(1, "Ali", DebtSide.I_OWE, "USD")
    private fun preview(count: Int = 2) = DebtDeletionPreview(debt,
        (1..count).map { Transaction.OwedBefore(it.toLong(), 1, debt.side, Money.of("10", "USD"), day, "Loan $it") },
        retainedEntryIds = listOf(100), remainingAfter = Money.of("5", "USD"))
    private fun draft(count: Int = 2, error: UiMessage? = null) = DeleteDebtDialogUiState(
        preview = preview(count), remainingAfterDisplay = "5.00 USD", error = error)

    @Test fun thePreviewNamesRemovedEntriesRetainedIncomeAndTheRemainingDebtBeforeConfirmation() {
        var confirmed = 0
        var dismissed = 0
        compose.setContent {
            MyWalletTheme(dynamicColor = false) {
                DeleteDebtDialog(draft(), isSubmitting = false, onConfirm = { confirmed++ }, onDismiss = { dismissed++ })
            }
        }
        compose.onNodeWithText("Loan 1").assertIsDisplayed()
        compose.onNodeWithText("Loan 2").assertIsDisplayed()
        compose.onNode(hasText("2", substring = true) and hasText("will be removed", substring = true)).assertIsDisplayed()
        compose.onNode(hasText("1", substring = true) and hasText("will stay", substring = true)).assertIsDisplayed()
        compose.onNode(hasText("Remaining after deletion", substring = true) and hasText("5.00 USD", substring = true))
            .assertIsDisplayed()
        compose.runOnIdle { assertEquals(0, confirmed) }
        compose.onNodeWithText("Delete debt").performClick()
        compose.runOnIdle { assertEquals(1, confirmed); assertEquals(0, dismissed) }
    }

    @Test fun pendingDeletionDisablesConfirmAndCancelAndGuardErrorIsAnnounced() {
        compose.setContent {
            MyWalletTheme(dynamicColor = false) {
                DeleteDebtDialog(draft(error = UiMessage.DebtBelowZero("Ali", Money.of("5", "USD"))),
                    isSubmitting = true, onConfirm = {}, onDismiss = {})
            }
        }
        compose.onNodeWithText("Delete debt").assertIsNotEnabled()
        compose.onNodeWithText("Cancel").assertIsNotEnabled()
        compose.onNode(hasText("That would take", substring = true) and hasText("Ali", substring = true))
            .performScrollTo().assertIsDisplayed()
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.LiveRegion, LiveRegionMode.Polite))
    }

    @Test fun theFinalPreviewEntryAndConfirmationRemainReachableInDarkRtlAtTwoHundredPercentFont() {
        var confirmed = false
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl,
                LocalDensity provides Density(density.density, 2f)) {
                MyWalletTheme(darkTheme = true, dynamicColor = false) {
                    DeleteDebtDialog(draft(20), isSubmitting = false, onConfirm = { confirmed = true }, onDismiss = {})
                }
            }
        }
        compose.onNodeWithText("Loan 20").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Delete debt").assertIsDisplayed().performClick()
        compose.runOnIdle { assertEquals(true, confirmed) }
    }
}
