package org.aristonis.mywallet.domain.usecase

import kotlinx.coroutines.test.runTest
import org.aristonis.mywallet.domain.error.WalletException
import org.aristonis.mywallet.domain.model.Category
import org.aristonis.mywallet.domain.model.CategoryKind
import org.aristonis.mywallet.domain.usecase.fake.FakeCategoryRepository
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.fail
import org.junit.Test

/**
 * The reassignment itself is a multi-table statement that has to run inside one Room transaction,
 * so it lives in the data layer and is proven by the instrumented tests. What belongs here is the
 * contract: which guards refuse the delete, and exactly what the use-case hands the adapter.
 */
class DeleteCategoryTest {

    private fun category(
        id: Long,
        name: String,
        kind: CategoryKind = CategoryKind.EXPENSE,
        parentId: Long? = null,
        systemKey: String? = null,
    ) = Category(id = id, name = name, kind = kind, parentId = parentId, systemKey = systemKey)

    @Test
    fun deletesAPlainCategoryAndReportsHowManyTransactionsMoved() = runTest {
        val repo = FakeCategoryRepository(listOf(category(1, "Food")))
        repo.affectedRows = 12

        val moved = DeleteCategory(repo).invoke(1)

        assertEquals(12, moved)
        assertEquals(0, repo.stored.size)
    }

    @Test
    fun handsTheAdapterTheFallbackKeyForTheCategorysOwnKind() = runTest {
        val repo = FakeCategoryRepository(listOf(category(1, "Salary", kind = CategoryKind.INCOME)))

        DeleteCategory(repo).invoke(1)

        val call = repo.lastDeleteCall
        assertNotNull(call)
        assertEquals(1L, call!!.categoryId)
        assertEquals(CategoryKind.INCOME, call.kind)
        assertEquals(Category.uncategorizedKeyFor(CategoryKind.INCOME), call.fallbackKey)
    }

    @Test
    fun anExpenseCategoryFallsBackToTheExpenseBucketNotTheIncomeOne() = runTest {
        // Getting this wrong moves expenses into an income bucket, which no report would flag.
        val repo = FakeCategoryRepository(listOf(category(1, "Food", kind = CategoryKind.EXPENSE)))

        DeleteCategory(repo).invoke(1)

        assertEquals(Category.uncategorizedKeyFor(CategoryKind.EXPENSE), repo.lastDeleteCall!!.fallbackKey)
    }

    @Test
    fun deletingASubCategoryDelegatesWithTheSubCategorysOwnId() = runTest {
        val repo = FakeCategoryRepository(listOf(category(1, "Food"), category(2, "Snacks", parentId = 1)))

        DeleteCategory(repo).invoke(2)

        assertEquals(2L, repo.lastDeleteCall!!.categoryId)
        assertEquals("the parent must survive", 1, repo.stored.count { it.id == 1L })
    }

    @Test
    fun deletingAParentTakesItsChildrenWithIt() = runTest {
        val repo = FakeCategoryRepository(
            listOf(category(1, "Food"), category(2, "Snacks", parentId = 1), category(3, "Drinks", parentId = 1)),
        )

        DeleteCategory(repo).invoke(1)

        assertEquals(0, repo.stored.size)
    }

    @Test
    fun refusesToDeleteAReservedBucketAndRemovesNothing() = runTest {
        // Deleting the fallback would leave later deletes with nowhere to move transactions.
        val repo = FakeCategoryRepository(
            listOf(category(1, "Uncategorized", systemKey = Category.uncategorizedKeyFor(CategoryKind.EXPENSE))),
        )
        try {
            DeleteCategory(repo).invoke(1)
            fail("expected SystemCategoryProtected")
        } catch (_: WalletException.SystemCategoryProtected) {
            // expected
        }
        assertEquals(1, repo.stored.size)
        assertNull("the adapter must never have been called", repo.lastDeleteCall)
    }

    @Test(expected = WalletException.CategoryNotFound::class)
    fun unknownCategoryFailsLoud() = runTest {
        DeleteCategory(FakeCategoryRepository()).invoke(99)
    }

    @Test
    fun aFailedGuardNeverReachesTheAdapter() = runTest {
        val repo = FakeCategoryRepository()
        try {
            DeleteCategory(repo).invoke(99)
        } catch (_: WalletException.CategoryNotFound) {
            // expected
        }
        assertNull(repo.lastDeleteCall)
    }
}
