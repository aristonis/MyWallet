package org.aristonis.mywallet.domain.usecase

import kotlinx.coroutines.test.runTest
import org.aristonis.mywallet.domain.error.WalletException
import org.aristonis.mywallet.domain.model.Category
import org.aristonis.mywallet.domain.model.CategoryKind
import org.aristonis.mywallet.domain.model.Transaction
import org.aristonis.mywallet.domain.usecase.fake.EntryWorld
import org.aristonis.mywallet.domain.usecase.fake.SaveEntryRig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A transaction's main category must be a top-level one, and its sub-category must be a child of
 * that exact category.
 *
 * Without the first rule a transaction can name a sub-category as its MAIN category, and deleting
 * that sub-category then leaves the row pointing at a row that no longer exists: the delete only
 * clears the finer `subCategoryId` column, and there is no foreign key on the main one. The money
 * silently drops out of every report and the confirmation still says nothing moved.
 */
class CategoryReferenceGuardTest {

    /** 10 Food (top) -> 11 Groceries; 20 Salary (top) -> 21 Bonus. */
    private fun rig() = SaveEntryRig(
        categories = listOf(
            Category(id = 10, name = "Food", kind = CategoryKind.EXPENSE),
            Category(id = 11, name = "Groceries", kind = CategoryKind.EXPENSE, parentId = 10),
            Category(id = 20, name = "Salary", kind = CategoryKind.INCOME),
            Category(id = 21, name = "Bonus", kind = CategoryKind.INCOME, parentId = 20),
        ),
    )

    private suspend fun SaveEntryRig.refusal(entry: Transaction): Throwable? =
        runCatching { saveEntry(entry, typedRates = emptyMap()) }.exceptionOrNull()

    private fun expense(categoryId: Long, subCategoryId: Long? = null) =
        EntryWorld.expense(amount = "12.99", categoryId = categoryId, subCategoryId = subCategoryId)

    @Test
    fun anExpenseCannotUseASubCategoryAsItsMainCategory() = runTest {
        val failure = rig().refusal(expense(categoryId = 11))

        assertTrue("was $failure", failure is WalletException.CategoryNotTopLevel)
    }

    @Test
    fun anIncomeCannotUseASubCategoryAsItsMainCategory() = runTest {
        val failure = rig().refusal(EntryWorld.income(amount = "500", categoryId = 21))

        assertTrue("was $failure", failure is WalletException.CategoryNotTopLevel)
    }

    @Test
    fun aRejectedCategoryReferenceStoresNothing() = runTest {
        val rig = rig()

        rig.refusal(expense(categoryId = 11))

        assertTrue(rig.entries.rows.value.isEmpty())
    }

    @Test
    fun aTopLevelCategoryWithAMatchingChildIsAccepted() = runTest {
        val rig = rig()

        rig.saveEntry(expense(categoryId = 10, subCategoryId = 11), typedRates = emptyMap())

        assertEquals(1, rig.entries.rows.value.size)
    }

    @Test
    fun aTopLevelCategoryWithNoSubCategoryIsAccepted() = runTest {
        val rig = rig()

        rig.saveEntry(expense(categoryId = 10), typedRates = emptyMap())

        assertEquals(1, rig.entries.rows.value.size)
    }

    @Test
    fun aSubCategoryBelongingToAnotherParentIsRejected() = runTest {
        // 21 is a child of Salary, not of Food — accepting it would file the spend under a label
        // that no breakdown could ever resolve against its own parent.
        val failure = rig().refusal(expense(categoryId = 10, subCategoryId = 21))

        assertTrue("was $failure", failure is WalletException.CategoryKindMismatch)
    }

    @Test
    fun anUnknownSubCategoryFailsLoud() = runTest {
        val failure = rig().refusal(expense(categoryId = 10, subCategoryId = 99))

        assertTrue("was $failure", failure is WalletException.CategoryNotFound)
    }
}
