package org.aristonis.mywallet.ui.reports

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.aristonis.mywallet.domain.model.TrackingPeriod
import org.aristonis.mywallet.domain.model.TrackingWindow
import org.aristonis.mywallet.ui.CategoryLabel
import org.aristonis.mywallet.ui.theme.MyWalletTheme
import org.aristonis.mywallet.ui.window.DateWindowActions
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.time.LocalDate

/**
 * A category only opens when there is something finer to show. Offering to expand a row that holds
 * nothing but its own remainder would be a control that does nothing.
 */
@RunWith(AndroidJUnit4::class)
class ReportsCategoriesTest {

    @get:Rule val compose = createComposeRule()

    private val food = CategoryRow(
        id = 7,
        label = CategoryLabel.Named("Food"),
        totalDisplay = "35.00 USD",
        subCategories = listOf(
            SubCategoryRow(id = 71, label = CategoryLabel.Named("Groceries"), totalDisplay = "30.00 USD"),
            SubCategoryRow(id = null, label = CategoryLabel.NoSubCategory, totalDisplay = "5.00 USD"),
        ),
    )
    private val transport = CategoryRow(
        id = 9,
        label = CategoryLabel.Named("Transport"),
        totalDisplay = "9.00 USD",
        subCategories = listOf(SubCategoryRow(id = null, label = CategoryLabel.NoSubCategory, totalDisplay = "9.00 USD")),
    )
    private val salary = CategoryRow(
        id = 1,
        label = CategoryLabel.Named("Salary"),
        totalDisplay = "100.00 USD",
        subCategories = listOf(SubCategoryRow(id = null, label = CategoryLabel.NoSubCategory, totalDisplay = "100.00 USD")),
    )

    /**
     * Scrolls the list until a row matching [matcher] is laid out, then returns that row. A lazy row
     * that is composed ahead of time but not yet placed reports placeholder bounds, so scrolling by
     * a node's own position can decide it is already visible; scrolling the list to the item works.
     */
    private fun scrollTo(matcher: SemanticsMatcher): SemanticsNodeInteraction {
        compose.onNode(hasScrollToIndexAction()).performScrollToNode(matcher)
        return compose.onNode(matcher)
    }

    private fun show() {
        compose.setContent {
            MyWalletTheme(dynamicColor = false) {
                ReportsContent(
                    state = ReportsUiState(
                        window = TrackingWindow.Period(TrackingPeriod.MONTH, LocalDate.of(2026, 7, 15)),
                        data = ReportsData.Ready(
                            incomeDisplay = "100.00 USD",
                            expenseDisplay = "44.00 USD",
                            netDisplay = "56.00 USD",
                            incomeCategories = listOf(salary),
                            expenseCategories = listOf(food, transport),
                        ),
                    ),
                    windowActions = DateWindowActions.None,
                )
            }
        }
    }

    @Test
    fun incomeAndSpendingBothHaveASection() {
        show()

        scrollTo(hasText("Income by category")).assertIsDisplayed()
        scrollTo(hasText("Salary")).assertIsDisplayed()
        scrollTo(hasText("Spending by category")).assertIsDisplayed()
    }

    @Test
    fun aCategoryOpensToShowItsSubCategories() {
        show()
        compose.onNodeWithText("Groceries").assertDoesNotExist()

        scrollTo(hasContentDescription("Sub-categories of Food")).performClick()

        scrollTo(hasText("Groceries")).assertIsDisplayed()
        scrollTo(hasText("No sub-category")).assertIsDisplayed()
    }

    @Test
    fun noExpandWithoutSubCategories() {
        show()

        compose.onNodeWithContentDescription("Sub-categories of Transport").assertDoesNotExist()
    }
}
