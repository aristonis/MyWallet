package org.aristonis.mywallet.ui.deduction

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.aristonis.mywallet.R
import org.aristonis.mywallet.domain.model.Category
import org.aristonis.mywallet.domain.model.CategoryKind
import org.aristonis.mywallet.domain.model.Currency
import org.aristonis.mywallet.domain.model.DeductionMode
import org.aristonis.mywallet.ui.message.UiMessage
import org.aristonis.mywallet.ui.theme.MyWalletTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The deduction editor shows field refusals where they belong, asks for a currency only for a fixed
 * amount, flags a rule whose expense category was deleted, and offers Retry only when reading again can help.
 */
@RunWith(AndroidJUnit4::class)
class DeductionEditorTest {
    @get:Rule val compose = createComposeRule()

    private fun text(id: Int) = InstrumentationRegistry.getInstrumentation().targetContext.getString(id)

    private val loaded = DeductionEditorUiState(
        name = "Tax",
        value = "10",
        currencyCode = "USD",
        currencies = listOf(Currency("USD", "$", 2)),
        isLoading = false,
    )

    private fun show(state: DeductionEditorUiState) {
        compose.setContent {
            MyWalletTheme(dynamicColor = false) {
                DeductionEditorContent(
                    state = state,
                    onNameChange = {},
                    onValueChange = {},
                    onModeSelect = {},
                    onCurrencySelect = {},
                    onTimingSelect = {},
                    onExpenseCategorySelect = {},
                    onExpenseSubCategorySelect = {},
                    onAutoAddToggle = {},
                    onEnabledChange = {},
                    onSubmit = {},
                    onRetry = {},
                )
            }
        }
    }

    private val hasError = SemanticsMatcher.keyIsDefined(SemanticsProperties.Error)

    @Test
    fun subCategoryPickerAppearsForASelectedParentWithChildren() {
        show(loaded.copy(expenseCategoryId = 1,
            expenseCategories = listOf(Category(1, "Bills", CategoryKind.EXPENSE)),
            expenseSubCategories = listOf(Category(3, "Internet", CategoryKind.EXPENSE, parentId = 1))))

        compose.onNodeWithText(text(R.string.deduction_expense_subcategory)).performScrollTo().assertIsDisplayed()
    }

    @Test
    fun subCategoryPickerIsAbsentWithoutASelectedParent() {
        show(loaded)

        compose.onNodeWithText(text(R.string.deduction_expense_subcategory)).assertDoesNotExist()
    }

    @Test
    fun subCategoryPickerIsAbsentForAParentWithoutChildren() {
        show(loaded.copy(expenseCategoryId = 1,
            expenseCategories = listOf(Category(1, "Bills", CategoryKind.EXPENSE))))

        compose.onNodeWithText(text(R.string.deduction_expense_subcategory)).assertDoesNotExist()
    }

    @Test
    fun nameErrorFromStateShowsAtTheNameField() {
        show(loaded.copy(name = "", nameError = UiMessage.NameRequired))

        compose.onNode(hasSetTextAction() and hasText(text(R.string.deduction_name)) and hasError)
            .assertIsDisplayed()
        compose.onNodeWithText(text(R.string.msg_name_required), useUnmergedTree = true).assertIsDisplayed()
        compose.onNode(hasSetTextAction() and hasText(text(R.string.deduction_value)))
            .assert(SemanticsMatcher.keyNotDefined(SemanticsProperties.Error))
    }

    @Test
    fun currencySelectorShowsOnlyInFixedMode() {
        show(loaded.copy(mode = DeductionMode.PERCENT))

        compose.onNodeWithText(text(R.string.deduction_currency)).assertDoesNotExist()
    }

    @Test
    fun currencySelectorShowsInFixedMode() {
        show(loaded.copy(mode = DeductionMode.FIXED))

        compose.onNodeWithText(text(R.string.deduction_currency)).assertIsDisplayed()
    }

    @Test
    fun missingExpenseCategoryShowsTheNoticeAndTheChooseAction() {
        show(loaded.copy(expenseCategoryMissing = true))

        compose.onNodeWithText(text(R.string.deduction_category_choose)).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText(text(R.string.deduction_category_missing)).performScrollTo().assertIsDisplayed()
    }

    @Test
    fun notFoundShowsItsMessageAndNoRetry() {
        show(DeductionEditorUiState(isLoading = false, notFound = true))

        compose.onNodeWithText(text(R.string.msg_deduction_item_not_found)).assertIsDisplayed()
        compose.onNodeWithText(text(R.string.action_retry)).assertDoesNotExist()
    }

    @Test
    fun transientLoadFailureOffersRetry() {
        show(DeductionEditorUiState(isLoading = false, loadFailed = true))

        compose.onNodeWithText(text(R.string.deductions_load_failed)).assertIsDisplayed()
        compose.onNodeWithText(text(R.string.action_retry)).assertIsDisplayed()
    }
}
