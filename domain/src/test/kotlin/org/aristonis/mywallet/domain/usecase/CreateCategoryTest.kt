package org.aristonis.mywallet.domain.usecase

import kotlinx.coroutines.test.runTest
import org.aristonis.mywallet.domain.error.WalletException
import org.aristonis.mywallet.domain.model.Category
import org.aristonis.mywallet.domain.model.CategoryKind
import org.aristonis.mywallet.domain.usecase.fake.FakeCategoryRepository
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CreateCategoryTest {

    private fun expense(id: Long, name: String, parentId: Long? = null) =
        Category(id = id, name = name, kind = CategoryKind.EXPENSE, parentId = parentId)

    @Test
    fun createsATopLevelCategory() = runTest {
        val repo = FakeCategoryRepository()
        val id = CreateCategory(repo).invoke("Food", CategoryKind.EXPENSE)

        val created = repo.stored.single()
        assertEquals(id, created.id)
        assertEquals("Food", created.name)
        assertEquals(CategoryKind.EXPENSE, created.kind)
        assertNull(created.parentId)
        assertNull("a user category carries no system key", created.systemKey)
    }

    @Test
    fun createsASubCategoryUnderAParentOfTheSameKind() = runTest {
        val repo = FakeCategoryRepository(listOf(expense(1, "Food")))
        val id = CreateCategory(repo).invoke("Groceries", CategoryKind.EXPENSE, parentId = 1)

        assertEquals(1L, repo.stored.first { it.id == id }.parentId)
    }

    @Test
    fun trimsSurroundingWhitespaceFromTheName() = runTest {
        val repo = FakeCategoryRepository()
        val id = CreateCategory(repo).invoke("  Food  ", CategoryKind.EXPENSE)

        assertEquals("Food", repo.stored.first { it.id == id }.name)
    }

    @Test(expected = WalletException.DuplicateCategoryName::class)
    fun rejectsADuplicateNameInTheSameKindAndParent() = runTest {
        val repo = FakeCategoryRepository(listOf(expense(1, "Food")))
        CreateCategory(repo).invoke("Food", CategoryKind.EXPENSE)
    }

    @Test(expected = WalletException.DuplicateCategoryName::class)
    fun duplicateCheckIgnoresCase() = runTest {
        // The DB index is case-insensitive, so the guard has to be too, or the insert fails
        // downstream with a raw constraint error instead of a typed one.
        val repo = FakeCategoryRepository(listOf(expense(1, "Food")))
        CreateCategory(repo).invoke("food", CategoryKind.EXPENSE)
    }

    @Test
    fun allowsTheSameNameUnderADifferentParent() = runTest {
        val repo = FakeCategoryRepository(
            listOf(expense(1, "Food"), expense(2, "Transport"), expense(3, "Other", parentId = 1)),
        )
        CreateCategory(repo).invoke("Other", CategoryKind.EXPENSE, parentId = 2)

        assertEquals(2, repo.stored.count { it.name == "Other" })
    }

    @Test
    fun allowsTheSameNameInTheOtherKind() = runTest {
        val repo = FakeCategoryRepository(listOf(expense(1, "Gifts")))
        CreateCategory(repo).invoke("Gifts", CategoryKind.INCOME)

        assertEquals(2, repo.stored.count { it.name == "Gifts" })
    }

    @Test(expected = WalletException.CategoryKindMismatch::class)
    fun rejectsASubCategoryWhoseKindDiffersFromItsParent() = runTest {
        val repo = FakeCategoryRepository(listOf(expense(1, "Food")))
        CreateCategory(repo).invoke("Salary", CategoryKind.INCOME, parentId = 1)
    }

    @Test(expected = WalletException.CategoryDepthExceeded::class)
    fun rejectsAParentThatIsItselfASubCategory() = runTest {
        // Two levels is a hard cap, enforced in the core rather than only in the picker.
        val repo = FakeCategoryRepository(listOf(expense(1, "Food"), expense(2, "Groceries", parentId = 1)))
        CreateCategory(repo).invoke("Fruit", CategoryKind.EXPENSE, parentId = 2)
    }

    @Test(expected = WalletException.CategoryNotFound::class)
    fun unknownParentFailsLoud() = runTest {
        CreateCategory(FakeCategoryRepository()).invoke("Groceries", CategoryKind.EXPENSE, parentId = 99)
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsABlankName() = runTest {
        CreateCategory(FakeCategoryRepository()).invoke("   ", CategoryKind.EXPENSE)
    }
}
