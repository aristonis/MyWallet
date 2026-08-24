package org.aristonis.mywallet.domain.usecase

import kotlinx.coroutines.test.runTest
import org.aristonis.mywallet.domain.error.WalletException
import org.aristonis.mywallet.domain.model.Category
import org.aristonis.mywallet.domain.model.CategoryKind
import org.aristonis.mywallet.domain.usecase.fake.FakeCategoryRepository
import org.junit.Assert.assertEquals
import org.junit.Test

class RenameCategoryTest {

    private fun expense(id: Long, name: String, parentId: Long? = null, systemKey: String? = null) =
        Category(id = id, name = name, kind = CategoryKind.EXPENSE, parentId = parentId, systemKey = systemKey)

    @Test
    fun renamesACategory() = runTest {
        val repo = FakeCategoryRepository(listOf(expense(1, "Food")))
        RenameCategory(repo).invoke(1, "Groceries")

        assertEquals("Groceries", repo.stored.single().name)
    }

    @Test
    fun keepsEverythingElseAboutTheRow() = runTest {
        // A rename must not quietly re-parent or re-kind the row.
        val repo = FakeCategoryRepository(listOf(expense(1, "Food"), expense(2, "Snacks", parentId = 1)))
        RenameCategory(repo).invoke(2, "Treats")

        val renamed = repo.stored.first { it.id == 2L }
        assertEquals(1L, renamed.parentId)
        assertEquals(CategoryKind.EXPENSE, renamed.kind)
    }

    @Test
    fun renamingToItsOwnNameIsNotADuplicate() = runTest {
        // The uniqueness check must exclude the row being renamed, or a no-op save fails.
        val repo = FakeCategoryRepository(listOf(expense(1, "Food")))
        RenameCategory(repo).invoke(1, "Food")

        assertEquals("Food", repo.stored.single().name)
    }

    @Test
    fun renamingOnlyTheCasingIsAllowed() = runTest {
        val repo = FakeCategoryRepository(listOf(expense(1, "food")))
        RenameCategory(repo).invoke(1, "Food")

        assertEquals("Food", repo.stored.single().name)
    }

    @Test(expected = WalletException.DuplicateCategoryName::class)
    fun rejectsANameAlreadyUsedBySibling() = runTest {
        val repo = FakeCategoryRepository(listOf(expense(1, "Food"), expense(2, "Transport")))
        RenameCategory(repo).invoke(2, "Food")
    }

    @Test
    fun renamingAReservedBucketIsRefusedAndChangesNothing() = runTest {
        // Identity is the system key, so the name is free to change in principle — but the two
        // buckets are app-owned, and letting the user rename one makes it unrecognisable in the UI.
        val repo = FakeCategoryRepository(listOf(expense(1, "Uncategorized", systemKey = "UNCATEGORIZED_EXPENSE")))
        try {
            RenameCategory(repo).invoke(1, "Junk")
            org.junit.Assert.fail("expected SystemCategoryProtected")
        } catch (_: WalletException.SystemCategoryProtected) {
            // expected
        }
        assertEquals("Uncategorized", repo.stored.single().name)
    }

    @Test(expected = WalletException.CategoryNotFound::class)
    fun unknownCategoryFailsLoud() = runTest {
        RenameCategory(FakeCategoryRepository()).invoke(99, "Whatever")
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsABlankName() = runTest {
        RenameCategory(FakeCategoryRepository(listOf(expense(1, "Food")))).invoke(1, "   ")
    }
}
