package org.aristonis.mywallet.ui.reports

import org.aristonis.mywallet.ui.CategoryLabel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReportsRowModelTest {

    private fun sub(id: Long?, name: String?) =
        SubCategoryRow(id, if (name == null) CategoryLabel.NoSubCategory else CategoryLabel.Named(name), "1.00 USD")

    private fun row(vararg subs: SubCategoryRow) =
        CategoryRow(1, CategoryLabel.Named("Food"), "9.00 USD", subs.toList())

    private fun ready(income: List<CategoryRow>, expense: List<CategoryRow>) = ReportsData.Ready(
        incomeDisplay = "0.00 USD", expenseDisplay = "0.00 USD", netDisplay = "0.00 USD",
        incomeCategories = income, expenseCategories = expense,
    )

    @Test
    fun theCollapsedRowNamesUpToTwoSubCategoriesInTheirOrder() {
        val food = row(sub(1, "Groceries"), sub(2, "Dining"), sub(3, "Snacks"))

        assertEquals(
            listOf(CategoryLabel.Named("Groceries"), CategoryLabel.Named("Dining")),
            food.previewParts,
        )
    }

    @Test
    fun theUnlabelledRemainderIsNeverNamedInThePreview() {
        val food = row(sub(1, "Groceries"), sub(null, null))

        assertEquals(listOf<CategoryLabel>(CategoryLabel.Named("Groceries")), food.previewParts)
    }

    @Test
    fun aCategoryWithOnlyItsRemainderHasNoPreview() {
        assertTrue(row(sub(null, null)).previewParts.isEmpty())
    }

    @Test
    fun aWindowWithNoCategoryOnEitherSideIsEmpty() {
        assertTrue(ready(emptyList(), emptyList()).isEmpty)
    }

    @Test
    fun oneCategoryOnEitherSideMakesTheWindowNonEmpty() {
        assertFalse(ready(listOf(row()), emptyList()).isEmpty)
        assertFalse(ready(emptyList(), listOf(row())).isEmpty)
    }
}
