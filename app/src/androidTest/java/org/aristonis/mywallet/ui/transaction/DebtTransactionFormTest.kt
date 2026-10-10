package org.aristonis.mywallet.ui.transaction

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isPopup
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.aristonis.mywallet.domain.model.DebtSide
import org.aristonis.mywallet.ui.theme.MyWalletTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DebtTransactionFormTest {
    @get:Rule val compose = createComposeRule()

    @Test fun borrowedFormSelectsAnActiveAccountAndHasNoTrackingRate() = accountForm(TransactionType.BORROW)
    @Test fun lentFormSelectsAnActiveAccountAndHasNoTrackingRate() = accountForm(TransactionType.LEND)
    @Test fun creditPurchaseFormPicksCurrencyAndExpenseCategory() = categoryForm(TransactionType.CREDIT_PURCHASE, "Food")
    @Test fun earnedUnpaidFormPicksCurrencyAndIncomeCategory() = categoryForm(TransactionType.EARNED_UNPAID, "Salary")

    @Test fun owedBeforeFormChangesCurrencyAndWhoOwesWithoutAnAccountOrCategory() {
        val form = show(TransactionType.OWED_BEFORE)
        compose.onNodeWithText("Account").assertDoesNotExist()
        compose.onNodeWithText("Category").assertDoesNotExist()
        compose.onNodeWithText("Who owes?").assertIsDisplayed()
        compose.onNodeWithText("I owe").performScrollTo().performClick()
        compose.onNodeWithText("Owed to me").performClick()
        chooseEuro()
        fillAndSave(form, "Amount (EUR)", "owed-before")
        compose.runOnIdle { assertEquals(DebtSide.OWED_TO_ME, form.submitted!!.debtSide) }
    }

    @Test fun aCounterpartySuggestionCanBeChosenBeforeSaving() {
        val form = show(TransactionType.BORROW)
        compose.onNodeWithText("Person or business").performScrollTo().performTextReplacement("Sa")
        compose.onNodeWithText("Sara").assertIsDisplayed().performClick()
        compose.onNodeWithText("Person or business").assertTextContains("Sara")
        compose.runOnIdle { assertEquals(emptyList<String>(), form.state.counterpartySuggestions) }
        fillAndSave(form, "Amount (USD)", "borrow-suggestion")
        compose.runOnIdle { assertEquals("Sara", form.submitted!!.counterparty) }
    }

    @Test fun saveIsReachableInDarkRtlAtTwoHundredPercentFont() {
        val form = show(TransactionType.EARNED_UNPAID, largeRtl = true)
        compose.onNodeWithText("Earned, not paid yet").assertIsDisplayed()
        compose.formScreenshot("earned-dark-rtl-font200-fields")
        fillAndSave(form, "Amount (USD)", "earned-dark-rtl-font200-save")
    }

    private fun accountForm(type: TransactionType) {
        val form = show(type)
        compose.onNodeWithText("Currency").assertDoesNotExist()
        compose.onNodeWithText("Category").assertDoesNotExist()
        compose.onNode(hasText("Cash", substring = true) and hasClickAction()).performScrollTo().performClick()
        compose.onNodeWithText("Archived", substring = true).assertDoesNotExist()
        compose.onNode(hasText("Bank", substring = true) and hasClickAction()).performClick()
        compose.onNodeWithText("EUR rate in SYP").assertDoesNotExist()
        fillAndSave(form, "Amount (EUR)", type.name.lowercase())
        compose.runOnIdle { assertEquals(2L, form.submitted!!.selectedAccountId) }
    }

    private fun categoryForm(type: TransactionType, category: String) {
        val form = show(type)
        compose.onNodeWithText("Account").assertDoesNotExist()
        chooseEuro()
        compose.onNode(hasText(category) and hasClickAction()).performScrollTo().performClick()
        compose.onNode(hasText(category) and hasAnyAncestor(isPopup())).performClick()
        compose.onNodeWithText("EUR rate in SYP").performScrollTo().assertIsDisplayed()
        fillAndSave(form, "Amount (EUR)", type.name.lowercase())
        compose.runOnIdle { assertEquals("EUR", form.submitted!!.currencyCode) }
    }

    private fun chooseEuro() {
        compose.onNode(hasText("USD", substring = true) and hasClickAction() and
            SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Button)).performScrollTo().performClick()
        compose.onNode(hasText("EUR", substring = true) and hasAnyAncestor(isDialog())).performClick()
    }

    private fun fillAndSave(form: TransactionFormHarness, amountLabel: String, evidence: String) {
        compose.onNodeWithText("Save transaction").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithText(amountLabel).performScrollTo().performTextReplacement("50")
        compose.formScreenshot("$evidence-fields")
        compose.onNodeWithText("Save transaction").performScrollTo().assertIsDisplayed().assertIsEnabled()
        compose.formScreenshot("$evidence-save")
        compose.onNodeWithText("Save transaction").performClick()
        compose.runOnIdle { assertEquals("50", form.submitted!!.amountInput) }
    }

    private fun show(type: TransactionType, largeRtl: Boolean = false): TransactionFormHarness {
        val form = TransactionFormHarness(type)
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalLayoutDirection provides if (largeRtl) LayoutDirection.Rtl else LayoutDirection.Ltr,
                LocalDensity provides Density(density.density, if (largeRtl) 2f else density.fontScale)) {
                MyWalletTheme(darkTheme = largeRtl, dynamicColor = false) { form.Content() }
            }
        }
        return form
    }
}
