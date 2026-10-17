package org.aristonis.mywallet.domain.usecase

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.aristonis.mywallet.domain.error.WalletException
import org.aristonis.mywallet.domain.model.Category
import org.aristonis.mywallet.domain.model.CategoryKind
import org.aristonis.mywallet.domain.port.TransactionRunner
import org.aristonis.mywallet.domain.usecase.fake.FakeCategoryRepository
import org.aristonis.mywallet.domain.usecase.fake.FakeCategoryRoleRepository
import org.aristonis.mywallet.domain.usecase.fake.FakeDeductionItemRepository
import org.aristonis.mywallet.domain.usecase.fake.deductionItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class SaveDeductionDefinitionTest {

    /** Restores the category table when the block fails, as the Room transaction would. */
    private class CategoryRollbackRunner(private val categories: FakeCategoryRepository) : TransactionRunner {
        var runs = 0
            private set

        override suspend fun <T> inTransaction(block: suspend () -> T): T {
            runs++
            val snapshot = categories.stored
            return try {
                block()
            } catch (error: Throwable) {
                categories.restoreSnapshot(snapshot)
                throw error
            }
        }
    }

    private class Rig(
        val items: FakeDeductionItemRepository = FakeDeductionItemRepository(),
        val categories: FakeCategoryRepository = FakeCategoryRepository(),
    ) {
        val runner = CategoryRollbackRunner(categories)
        val save = SaveDeductionDefinition(
            items,
            categories,
            CreateCategory(categories, FakeCategoryRoleRepository(), runner),
            SaveDeductionItem(items),
            runner,
        )
    }

    private fun expense(id: Long, name: String, parentId: Long? = null) =
        Category(id = id, name = name, kind = CategoryKind.EXPENSE, parentId = parentId)

    @Test
    fun omittedCategoryCreatesATopLevelExpenseNamedAfterTheRule() = runTest {
        val rig = Rig()

        val id = rig.save(deductionItem(name = " Bill "), categoryId = null)

        val created = rig.categories.stored.single()
        assertEquals("Bill", created.name)
        assertEquals(CategoryKind.EXPENSE, created.kind)
        assertEquals(created.id, rig.items.findById(id)?.expenseCategoryId)
    }

    @Test
    fun omittedCategoryReusesAMatchingTopLevelExpenseIgnoringCase() = runTest {
        val rig = Rig(categories = FakeCategoryRepository(listOf(expense(5, "BILL"))))

        val id = rig.save(deductionItem(name = "Bill"), categoryId = null)

        assertEquals(1, rig.categories.stored.size)
        assertEquals(5L, rig.items.findById(id)?.expenseCategoryId)
    }

    @Test
    fun omittedCategoryDoesNotReuseASubCategoryOrAnIncomeCategoryWithTheName() = runTest {
        val existing = listOf(
            expense(1, "Home"),
            expense(2, "Bill", parentId = 1),
            Category(id = 3, name = "Bill", kind = CategoryKind.INCOME),
        )
        val rig = Rig(categories = FakeCategoryRepository(existing))

        val id = rig.save(deductionItem(name = "Bill"), categoryId = null)

        val chosen = rig.items.findById(id)?.expenseCategoryId
        val category = rig.categories.stored.single { it.id == chosen }
        assertEquals(CategoryKind.EXPENSE, category.kind)
        assertEquals(null, category.parentId)
        assertEquals(4, rig.categories.stored.size)
    }

    @Test
    fun chosenExpenseCategoryIsStoredAsIs() = runTest {
        val rig = Rig(categories = FakeCategoryRepository(listOf(expense(7, "Taxes"))))

        val id = rig.save(deductionItem(name = "Tax"), categoryId = 7)

        assertEquals(7L, rig.items.findById(id)?.expenseCategoryId)
        assertEquals(1, rig.categories.stored.size)
    }

    @Test
    fun subCategoryOfAnExpenseParentIsAccepted() = runTest {
        val rig = Rig(categories = FakeCategoryRepository(listOf(
            expense(1, "Bills"), expense(2, "Internet", parentId = 1))))

        val id = rig.save(deductionItem(name = "Internet"), categoryId = 2)

        assertEquals(2L, rig.items.findById(id)?.expenseCategoryId)
    }

    @Test
    fun expenseSubCategoryUnderAnIncomeParentIsRefusedWithoutWrite() = runTest {
        val rig = Rig(categories = FakeCategoryRepository(listOf(
            Category(1, "Salary", CategoryKind.INCOME), expense(2, "Internet", parentId = 1))))

        val failure = failureOf<WalletException.CategoryWrongKind> {
            rig.save(deductionItem(name = "Internet"), categoryId = 2)
        }

        assertEquals(1L, failure.categoryId)
        assertTrue(rig.items.observeAll().first().isEmpty())
    }

    @Test
    fun aRuleNameThatFoldsToAnotherRulesKeyIsRefusedBeforeAnyWrite() = runTest {
        val rig = Rig(items = FakeDeductionItemRepository(listOf(deductionItem(id = 1, name = "Tax"))))

        val failure = failureOf<WalletException.DeductionItemNameTaken> {
            rig.save(deductionItem(name = " ‎TAX "), categoryId = null)
        }

        assertEquals(" ‎TAX ", failure.name)
        assertTrue(rig.categories.stored.isEmpty())
        assertEquals(1, rig.items.observeAll().first().size)
    }

    @Test
    fun editingARuleKeepsItsOwnNameWithoutARefusal() = runTest {
        val rig = Rig(
            items = FakeDeductionItemRepository(listOf(deductionItem(id = 1, name = "Tax"))),
            categories = FakeCategoryRepository(listOf(expense(7, "Taxes"))),
        )

        rig.save(deductionItem(id = 1, name = "Tax", value = "12.5"), categoryId = 7)

        assertEquals(0, "12.5".toBigDecimal().compareTo(rig.items.findById(1)!!.value))
    }

    @Test
    fun aRefusedRuleLeavesNoOrphanAutomaticCategory() = runTest {
        val rig = Rig()

        failureOf<WalletException.DeductionItemNotFound> {
            rig.save(deductionItem(id = 99, name = "Tax"), categoryId = null)
        }

        assertTrue(rig.categories.stored.isEmpty())
        assertTrue(rig.items.observeAll().first().isEmpty())
    }

    @Test
    fun ruleAndAutomaticCategoryAreWrittenInsideOneTransaction() = runTest {
        val rig = Rig()

        rig.save(deductionItem(name = "Tax"), categoryId = null)

        // The outer run plus the nested CreateCategory run; Room joins the nested one to the outer.
        assertEquals(2, rig.runner.runs)
    }

    @Test
    fun aMissingChosenCategoryIsRefusedWithItsId() = runTest {
        val rig = Rig()

        val failure = failureOf<WalletException.CategoryNotFound> {
            rig.save(deductionItem(name = "Tax"), categoryId = 9)
        }

        assertEquals(9L, failure.id)
        assertTrue(rig.items.observeAll().first().isEmpty())
    }

    @Test
    fun anIncomeCategoryIsRefusedAsTheExpenseCategory() = runTest {
        val rig = Rig(categories = FakeCategoryRepository(listOf(Category(3, "Salary", CategoryKind.INCOME))))

        failureOf<WalletException.CategoryWrongKind> { rig.save(deductionItem(name = "Tax"), categoryId = 3) }

        assertTrue(rig.items.observeAll().first().isEmpty())
    }

    /** Runs a suspending call and returns the typed failure it must throw. */
    private inline fun <reified T : Throwable> failureOf(crossinline call: suspend () -> Unit): T =
        assertThrows(T::class.java) { runBlocking { call() } }
}
