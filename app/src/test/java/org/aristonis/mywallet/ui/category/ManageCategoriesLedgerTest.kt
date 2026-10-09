package org.aristonis.mywallet.ui.category

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.aristonis.mywallet.RecordingErrorReporter
import org.aristonis.mywallet.domain.error.WalletException
import org.aristonis.mywallet.domain.model.Category
import org.aristonis.mywallet.domain.model.CategoryKind
import org.aristonis.mywallet.domain.usecase.CreateCategory
import org.aristonis.mywallet.domain.usecase.DeleteCategory
import org.aristonis.mywallet.domain.usecase.ObserveCategoryUsage
import org.aristonis.mywallet.domain.usecase.RenameCategory
import org.aristonis.mywallet.ui.HealthLedger
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Manage Categories takes the number of entries behind each category from the ledger's usage read
 * and checks the ledger's health, instead of listing every transaction to count them.
 *
 * Assumed constructor change, called by name here:
 * `ManageCategoriesViewModel(categories, observeCategoryUsage: ObserveCategoryUsage,
 * observeLedgerHealth: ObserveLedgerHealth, createCategory, renameCategory, deleteCategory, errors)`,
 * which reads no entry list.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ManageCategoriesLedgerTest {

    private val dispatcher = StandardTestDispatcher()

    @Before fun setUp() = Dispatchers.setMain(dispatcher)

    @After fun tearDown() = Dispatchers.resetMain()

    private val food = Category(id = 10, name = "Food", kind = CategoryKind.EXPENSE)
    private val groceries = Category(id = 11, name = "Groceries", kind = CategoryKind.EXPENSE, parentId = 10)
    private val restaurants = Category(id = 12, name = "Restaurants", kind = CategoryKind.EXPENSE, parentId = 10)
    private val salary = Category(id = 20, name = "Salary", kind = CategoryKind.INCOME)

    private val errors = RecordingErrorReporter()

    private fun viewModel(
        ledger: HealthLedger,
        categories: FakeManageCategoryRepository = FakeManageCategoryRepository(listOf(food, groceries, restaurants, salary)),
    ) = ManageCategoriesViewModel(
        categories = categories,
        observeCategoryUsage = ObserveCategoryUsage(ledger),
        observeLedgerHealth = ledger.health(),
        createCategory = CreateCategory(categories),
        renameCategory = RenameCategory(categories),
        deleteCategory = DeleteCategory(categories),
        errors = errors,
    )

    @Test
    fun `an unknown kind in ledger health shows the cannot-be-read state with Retry`() = runTest(dispatcher) {
        val ledger = HealthLedger().apply { plantUnknownKind(id = 7) }
        val vm = viewModel(ledger)
        dispatcher.scheduler.advanceUntilIdle()

        val state = vm.state.value
        assertTrue(state.loadFailed)
        assertFalse(state.isLoading)
        assertTrue("no counts are quoted over a row that cannot be read", state.expense.isEmpty() && state.income.isEmpty())
        assertEquals(7L, (errors.reported.single() as WalletException.UnreadableRow).id)
    }

    @Test
    fun `Retry reads the ledger health again and lists the categories once the row is readable`() = runTest(dispatcher) {
        val ledger = HealthLedger().apply { plantUnknownKind() }
        val vm = viewModel(ledger)
        dispatcher.scheduler.advanceUntilIdle()
        assertTrue(vm.state.value.loadFailed)
        val readsBefore = ledger.healthReads

        ledger.heal()
        vm.retry()
        dispatcher.scheduler.advanceUntilIdle()

        assertTrue("retry must read the health again", ledger.healthReads > readsBefore)
        assertFalse(vm.state.value.loadFailed)
        assertEquals(listOf("Food"), vm.state.value.expense.map { it.category.name })
    }

    @Test
    fun `category counts come from ObserveCategoryUsage`() = runTest(dispatcher) {
        // Three entries on Food, one of them under Groceries and one under Restaurants.
        val ledger = HealthLedger().apply { usage.value = mapOf(10L to 3, 11L to 1, 12L to 1) }
        val vm = viewModel(ledger)
        dispatcher.scheduler.advanceUntilIdle()

        val foodRow = vm.state.value.expense.single()
        assertEquals("a parent counts its entries once, not once more per child", 3, foodRow.affectedTransactions)
        assertEquals(3, vm.affectedBy(10))
        assertEquals(1, vm.affectedBy(11))
        assertEquals(1, vm.affectedBy(12))
    }

    @Test
    fun `a category no entry uses counts zero`() = runTest(dispatcher) {
        val ledger = HealthLedger().apply { usage.value = mapOf(10L to 3) }
        val vm = viewModel(ledger)
        dispatcher.scheduler.advanceUntilIdle()

        assertEquals(0, vm.state.value.income.single().affectedTransactions)
        assertEquals(0, vm.affectedBy(20))
    }

    @Test
    fun `counts follow the usage read as entries change`() = runTest(dispatcher) {
        val ledger = HealthLedger().apply { usage.value = mapOf(10L to 1) }
        val vm = viewModel(ledger)
        dispatcher.scheduler.advanceUntilIdle()
        assertEquals(1, vm.state.value.expense.single().affectedTransactions)

        ledger.usage.value = mapOf(10L to 4, 20L to 2)
        dispatcher.scheduler.advanceUntilIdle()

        assertEquals(4, vm.state.value.expense.single().affectedTransactions)
        assertEquals(2, vm.state.value.income.single().affectedTransactions)
    }

    @Test
    fun `a usage read that fails shows the cannot-be-read state instead of closing the app`() = runTest(dispatcher) {
        val ledger = object : HealthLedger() {
            override fun observeCategoryUsage(): Flow<Map<Long, Int>> = flow { throw IllegalStateException("usage could not be read") }
        }
        val vm = viewModel(ledger)
        dispatcher.scheduler.advanceUntilIdle()

        assertTrue(vm.state.value.loadFailed)
        assertEquals(listOf("usage could not be read"), errors.reported.map { it.message })
    }
}
