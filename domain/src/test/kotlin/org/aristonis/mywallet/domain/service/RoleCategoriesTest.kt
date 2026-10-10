package org.aristonis.mywallet.domain.service

import kotlinx.coroutines.test.runTest
import org.aristonis.mywallet.domain.model.Category
import org.aristonis.mywallet.domain.model.CategoryKind
import org.aristonis.mywallet.domain.model.CategoryRole
import org.aristonis.mywallet.domain.usecase.fake.FakeCategoryRepository
import org.aristonis.mywallet.domain.usecase.fake.FakeCategoryRoleRepository
import org.junit.Assert.assertEquals
import org.junit.Test

class RoleCategoriesTest {

    private val fees = Category(id = 1, name = "Fees", kind = CategoryKind.EXPENSE)
    private val bank = Category(id = 2, name = "Bank", kind = CategoryKind.EXPENSE)

    @Test
    fun aPickedCategoryWinsAndDoesNotRebindTheRole() = runTest {
        val roles = FakeCategoryRoleRepository(mapOf(CategoryRole.FEES to fees.id))
        val service = RoleCategories(roles, FakeCategoryRepository(listOf(fees, bank)))

        val id = service.categoryFor(CategoryRole.FEES, picked = bank.id)

        assertEquals(bank.id, id)
        assertEquals(mapOf(CategoryRole.FEES to fees.id), roles.stored)
    }

    @Test
    fun theRolesCategoryIsUsedWhenNothingWasPicked() = runTest {
        val roles = FakeCategoryRoleRepository(mapOf(CategoryRole.FEES to fees.id))
        val service = RoleCategories(roles, FakeCategoryRepository(listOf(fees, bank)))

        assertEquals(fees.id, service.categoryFor(CategoryRole.FEES, picked = null))
    }

    @Test
    fun aMissingExpenseRoleFallsBackToTheExpenseBucket() = runTest {
        val categories = FakeCategoryRepository(listOf(bank))
        val service = RoleCategories(FakeCategoryRoleRepository(), categories)

        val id = service.categoryFor(CategoryRole.LOST_DEBT, picked = null)

        val bucket = categories.stored.single { it.id == id }
        assertEquals(Category.uncategorizedKeyFor(CategoryKind.EXPENSE), bucket.systemKey)
        assertEquals(CategoryKind.EXPENSE, bucket.kind)
    }

    @Test
    fun aMissingIncomeRoleFallsBackToTheIncomeBucket() = runTest {
        val categories = FakeCategoryRepository(listOf(bank))
        val service = RoleCategories(FakeCategoryRoleRepository(), categories)

        val id = service.categoryFor(CategoryRole.INTEREST_IN, picked = null)

        val bucket = categories.stored.single { it.id == id }
        assertEquals(Category.uncategorizedKeyFor(CategoryKind.INCOME), bucket.systemKey)
        assertEquals(CategoryKind.INCOME, bucket.kind)
    }

    @Test
    fun theBucketIsCreatedOnceAcrossTwoCalls() = runTest {
        val categories = FakeCategoryRepository(listOf(bank))
        val service = RoleCategories(FakeCategoryRoleRepository(), categories)

        val first = service.categoryFor(CategoryRole.FEES, picked = null)
        val second = service.categoryFor(CategoryRole.LOST_DEBT, picked = null)

        assertEquals(first, second)
        assertEquals(1, categories.stored.count { it.systemKey != null })
    }
}
