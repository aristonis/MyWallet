package org.aristonis.mywallet.domain.usecase

import kotlinx.coroutines.test.runTest
import org.aristonis.mywallet.domain.model.Category
import org.aristonis.mywallet.domain.model.CategoryKind
import org.aristonis.mywallet.domain.model.CategoryRole
import org.aristonis.mywallet.domain.port.CategoryRoleRepository
import org.aristonis.mywallet.domain.port.TransactionRunner
import org.aristonis.mywallet.domain.service.RoleCategories
import org.aristonis.mywallet.domain.usecase.fake.FakeCategoryRepository
import org.aristonis.mywallet.domain.usecase.fake.InjectedFault
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

class RoleFallbackTest {
    @Test fun creatingTrimmedMixedCaseFeesRebindsOnlyTheMissingExpenseRole() = runTest {
        val rig = RoleCreationRig()
        val id = rig.create("  fEeS  ", CategoryKind.EXPENSE)
        assertEquals(id, rig.roles.categoryFor(CategoryRole.FEES))
        assertEquals(id, rig.fallback.categoryFor(CategoryRole.FEES, null))
        assertEquals("fEeS", rig.categories.stored.single().name)
    }

    @Test fun eachLiteralNameBindsItsOwnKindAndRole() = runTest {
        for (role in CategoryRole.entries) {
            val rig = RoleCreationRig()
            val id = rig.create(role.literalName.lowercase(Locale.ROOT), role.kind)
            assertEquals("role $role", id, rig.roles.categoryFor(role))
            assertTrue(rig.roles.stored.keys == setOf(role))
        }
    }

    @Test fun caseMatchingUsesRootLocaleWhenTheDeviceUsesTurkish() = runTest {
        val original = Locale.getDefault()
        try {
            Locale.setDefault(Locale.forLanguageTag("tr-TR"))
            val rig = RoleCreationRig()
            val id = rig.create("INTEREST", CategoryKind.INCOME)
            assertEquals(id, rig.roles.categoryFor(CategoryRole.INTEREST_IN))
        } finally {
            Locale.setDefault(original)
        }
    }

    @Test fun anIncomeNamedFeesAndASubcategoryNamedFeesDoNotBindTheExpenseRole() = runTest {
        val rig = RoleCreationRig(initialCategories = listOf(category(9, "Food")))
        rig.create("Fees", CategoryKind.INCOME)
        rig.create("Fees", CategoryKind.EXPENSE, parentId = 9)
        assertNull(rig.roles.categoryFor(CategoryRole.FEES))
    }

    @Test fun creatingFeesDoesNotReplaceAnExistingBindingToARenamedCategory() = runTest {
        val rig = RoleCreationRig(listOf(category(9, "Service costs")), mapOf(CategoryRole.FEES to 9))
        rig.create("Fees", CategoryKind.EXPENSE)
        assertEquals(9L, rig.roles.categoryFor(CategoryRole.FEES))
        assertEquals(9L, rig.fallback.categoryFor(CategoryRole.FEES, null))
    }

    @Test fun renamingToFeesAndPickingACategoryNeverBindARole() = runTest {
        val rig = RoleCreationRig(listOf(category(9, "Service costs")))
        RenameCategory(rig.categories)(9, "Fees")
        assertEquals(9L, rig.fallback.categoryFor(CategoryRole.FEES, 9))
        assertNull(rig.roles.categoryFor(CategoryRole.FEES))
    }

    @Test fun repeatedMissingRoleFallbacksReuseOneBucketWithoutBindingTheRole() = runTest {
        val rig = RoleCreationRig()
        val first = rig.runner.inTransaction { rig.fallback.categoryFor(CategoryRole.FEES, null) }
        val second = rig.runner.inTransaction { rig.fallback.categoryFor(CategoryRole.INTEREST_OUT, null) }
        assertEquals(first, second)
        assertEquals(1, rig.categories.stored.size)
        assertNull(rig.roles.categoryFor(CategoryRole.FEES))
        assertNull(rig.roles.categoryFor(CategoryRole.INTEREST_OUT))
    }

    @Test fun creatingFeesLeavesEarlierBucketSelectionsUnchanged() = runTest {
        val rig = RoleCreationRig()
        val oldBucket = rig.runner.inTransaction { rig.fallback.categoryFor(CategoryRole.FEES, null) }
        val fees = rig.create("Fees", CategoryKind.EXPENSE)
        assertEquals(fees, rig.fallback.categoryFor(CategoryRole.FEES, null))
        assertTrue(oldBucket != fees)
        assertEquals(oldBucket, rig.categories.stored.single { it.systemKey != null }.id)
    }

    @Test fun failureAfterBindingRollsBackBothTheCategoryAndItsBinding() = runTest {
        val rig = RoleCreationRig(failAfterBind = true)
        val before = rig.categories.stored
        val failure = runCatching { rig.create("Fees", CategoryKind.EXPENSE) }.exceptionOrNull()
        assertTrue("was $failure", failure is InjectedFault)
        assertEquals(before, rig.categories.stored)
        assertTrue(rig.roles.stored.isEmpty())
    }

    private fun category(id: Long, name: String) = Category(id, name, CategoryKind.EXPENSE)
}

internal class RoleCreationRig(
    initialCategories: List<Category> = emptyList(),
    initialRoles: Map<CategoryRole, Long> = emptyMap(),
    failAfterBind: Boolean = false,
) {
    val categories = FakeCategoryRepository(initialCategories)
    val roles = CreationRoleRepository(initialRoles, failAfterBind)
    val runner = object : TransactionRunner {
        override suspend fun <T> inTransaction(block: suspend () -> T): T {
            val categoriesBefore = categories.stored
            val rolesBefore = roles.stored
            try {
                return block()
            } catch (failure: Throwable) {
                categories.restoreSnapshot(categoriesBefore)
                roles.restoreSnapshot(rolesBefore)
                throw failure
            }
        }
    }
    val fallback = RoleCategories(roles, categories)
    val create = CreateCategory(categories, roles, runner)
}

internal class CreationRoleRepository(
    initial: Map<CategoryRole, Long>,
    private val failAfterBind: Boolean,
) : CategoryRoleRepository {
    var stored: Map<CategoryRole, Long> = initial
        private set

    override suspend fun categoryFor(role: CategoryRole): Long? = stored[role]

    override suspend fun bindIfMissing(role: CategoryRole, categoryId: Long): Boolean {
        if (role in stored) return false
        stored = stored + (role to categoryId)
        if (failAfterBind) throw InjectedFault()
        return true
    }

    fun restoreSnapshot(snapshot: Map<CategoryRole, Long>) { stored = snapshot }
}
