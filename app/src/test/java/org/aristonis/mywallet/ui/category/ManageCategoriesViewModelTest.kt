package org.aristonis.mywallet.ui.category

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.aristonis.mywallet.domain.error.WalletException
import org.aristonis.mywallet.domain.model.Category
import org.aristonis.mywallet.domain.model.CategoryKind
import org.aristonis.mywallet.domain.model.Money
import org.aristonis.mywallet.domain.model.Transaction
import org.aristonis.mywallet.domain.usecase.CreateCategory
import org.aristonis.mywallet.domain.usecase.DeleteCategory
import org.aristonis.mywallet.domain.usecase.RenameCategory
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.LocalDate

@OptIn(ExperimentalCoroutinesApi::class)
class ManageCategoriesViewModelTest {

    private val dispatcher = StandardTestDispatcher()

    @Before fun setUp() = Dispatchers.setMain(dispatcher)

    @After fun tearDown() = Dispatchers.resetMain()

    private val food = Category(id = 10, name = "Food", kind = CategoryKind.EXPENSE)
    private val groceries = Category(id = 11, name = "Groceries", kind = CategoryKind.EXPENSE, parentId = 10)
    private val restaurants = Category(id = 12, name = "Restaurants", kind = CategoryKind.EXPENSE, parentId = 10)
    private val salary = Category(id = 20, name = "Salary", kind = CategoryKind.INCOME)
    private val bucket = Category(
        id = 99,
        name = "Uncategorized",
        kind = CategoryKind.EXPENSE,
        systemKey = Category.uncategorizedKeyFor(CategoryKind.EXPENSE),
    )

    private fun expense(id: Long, categoryId: Long, subCategoryId: Long? = null) = Transaction.Expense(
        id = id,
        accountId = 1,
        amount = Money.of("10.00", "USD"),
        categoryId = categoryId,
        subCategoryId = subCategoryId,
        date = LocalDate.of(2026, 2, 1),
    )

    private fun viewModel(
        categories: FakeManageCategoryRepository,
        transactions: FakeCategoryTransactionRepository = FakeCategoryTransactionRepository(),
    ) = ManageCategoriesViewModel(
        categories = categories,
        transactions = transactions,
        createCategory = CreateCategory(categories),
        renameCategory = RenameCategory(categories),
        deleteCategory = DeleteCategory(categories),
    )

    @Test
    fun groupsByKindAndNestsChildrenUnderTheirParent() = runTest(dispatcher) {
        val repo = FakeManageCategoryRepository(listOf(food, groceries, restaurants, salary))
        val vm = viewModel(repo)
        dispatcher.scheduler.advanceUntilIdle()

        val state = vm.state.value
        assertEquals(listOf("Salary"), state.income.map { it.category.name })
        assertEquals(listOf("Food"), state.expense.map { it.category.name })
        assertEquals(listOf("Groceries", "Restaurants"), state.expense.single().children.map { it.name })
    }

    @Test
    fun aSubCategoryIsNeverListedAsATopLevelRow() = runTest(dispatcher) {
        val repo = FakeManageCategoryRepository(listOf(food, groceries))
        val vm = viewModel(repo)
        dispatcher.scheduler.advanceUntilIdle()

        assertEquals(1, vm.state.value.expense.size)
    }

    @Test
    fun countsTheTransactionsADeleteWouldReFile_includingThoseUnderItsChildren() = runTest(dispatcher) {
        // The confirmation quotes this number, so it has to cover the children too — a user who sees
        // "1 transaction" and loses three has been misled at the one moment they were asked to decide.
        val repo = FakeManageCategoryRepository(listOf(food, groceries, restaurants))
        val txs = FakeCategoryTransactionRepository(
            listOf(
                expense(id = 1, categoryId = 10),
                expense(id = 2, categoryId = 10, subCategoryId = 11),
                expense(id = 3, categoryId = 10, subCategoryId = 12),
            ),
        )
        val vm = viewModel(repo, txs)
        dispatcher.scheduler.advanceUntilIdle()

        assertEquals(3, vm.state.value.expense.single().affectedTransactions)
    }

    @Test
    fun aSubCategoryRowCountsOnlyItsOwnTransactions() = runTest(dispatcher) {
        val repo = FakeManageCategoryRepository(listOf(food, groceries, restaurants))
        val txs = FakeCategoryTransactionRepository(
            listOf(expense(id = 1, categoryId = 10), expense(id = 2, categoryId = 10, subCategoryId = 11)),
        )
        val vm = viewModel(repo, txs)
        dispatcher.scheduler.advanceUntilIdle()

        val children = vm.state.value.expense.single().children
        assertEquals(1, children.first { it.id == groceries.id }.let { vm.affectedBy(it.id) })
        assertEquals(0, children.first { it.id == restaurants.id }.let { vm.affectedBy(it.id) })
    }

    @Test
    fun surfacesTheSystemKeySoTheScreenCanLabelTheBucketItself() = runTest(dispatcher) {
        // The stored name is a placeholder; the screen resolves what the user sees from the key, so
        // the bucket stays translatable without anything having to rewrite the row.
        val repo = FakeManageCategoryRepository(listOf(food, bucket))
        val vm = viewModel(repo)
        dispatcher.scheduler.advanceUntilIdle()

        val bucketRow = vm.state.value.expense.first { it.category.id == bucket.id }
        assertEquals(Category.uncategorizedKeyFor(CategoryKind.EXPENSE), bucketRow.systemKey)
        assertTrue("the bucket cannot be renamed or deleted", bucketRow.isProtected)
        assertNull("a user category carries no key", vm.state.value.expense.first { it.category.id == food.id }.systemKey)
    }

    @Test
    fun createAddsATopLevelCategory() = runTest(dispatcher) {
        val repo = FakeManageCategoryRepository(listOf(food))
        val vm = viewModel(repo)
        vm.create("Transport", CategoryKind.EXPENSE, parentId = null)
        dispatcher.scheduler.advanceUntilIdle()

        assertTrue(repo.stored.any { it.name == "Transport" && it.parentId == null })
    }

    @Test
    fun createAddsASubCategoryUnderItsParent() = runTest(dispatcher) {
        val repo = FakeManageCategoryRepository(listOf(food))
        val vm = viewModel(repo)
        vm.create("Snacks", CategoryKind.EXPENSE, parentId = food.id)
        dispatcher.scheduler.advanceUntilIdle()

        assertEquals(food.id, repo.stored.first { it.name == "Snacks" }.parentId)
    }

    @Test
    fun aDuplicateNameSurfacesAsAnErrorRatherThanCrashing() = runTest(dispatcher) {
        val repo = FakeManageCategoryRepository(listOf(food))
        val vm = viewModel(repo)
        vm.create("Food", CategoryKind.EXPENSE, parentId = null)
        dispatcher.scheduler.advanceUntilIdle()

        assertNotNull(vm.state.value.error)
        assertEquals("nothing was added", 1, repo.stored.size)
    }

    @Test
    fun renameChangesTheName() = runTest(dispatcher) {
        val repo = FakeManageCategoryRepository(listOf(food))
        val vm = viewModel(repo)
        vm.rename(food.id, "Groceries & dining")
        dispatcher.scheduler.advanceUntilIdle()

        assertEquals("Groceries & dining", repo.stored.single().name)
    }

    @Test
    fun deleteReportsHowManyTransactionsMoved() = runTest(dispatcher) {
        val repo = FakeManageCategoryRepository(listOf(food, groceries))
        repo.affectedRows = 7
        val vm = viewModel(repo)
        vm.delete(food.id)
        dispatcher.scheduler.advanceUntilIdle()

        assertEquals(7, vm.state.value.lastDelete?.affected)
        assertFalse("Food is top-level, so its spend moved to the bucket", vm.state.value.lastDelete!!.wasSubCategory)
    }

    @Test
    fun acknowledgingClearsTheMovedReport() = runTest(dispatcher) {
        // One-shot, or navigating back to this screen replays a message about a delete long finished.
        val repo = FakeManageCategoryRepository(listOf(food))
        repo.affectedRows = 2
        val vm = viewModel(repo)
        vm.delete(food.id)
        dispatcher.scheduler.advanceUntilIdle()
        vm.acknowledge()

        assertNull(vm.state.value.lastDelete)
        assertNull(vm.state.value.error)
    }

    @Test
    fun deletingASubCategoryReportsThatTheSpendKeptItsCategory() = runTest(dispatcher) {
        // The two cases end differently, so they cannot share one message: this one clears the finer
        // label and leaves the money under Food. Saying "moved to Uncategorized" names the wrong place.
        val repo = FakeManageCategoryRepository(listOf(food, groceries))
        repo.affectedRows = 3
        val vm = viewModel(repo)
        dispatcher.scheduler.advanceUntilIdle()
        vm.delete(groceries.id)
        dispatcher.scheduler.advanceUntilIdle()

        assertEquals(3, vm.state.value.lastDelete?.affected)
        assertTrue(vm.state.value.lastDelete!!.wasSubCategory)
    }

    @Test
    fun refusingToDeleteTheBucketSurfacesAsAnErrorAndRemovesNothing() = runTest(dispatcher) {
        val repo = FakeManageCategoryRepository(listOf(food, bucket))
        val vm = viewModel(repo)
        vm.delete(bucket.id)
        dispatcher.scheduler.advanceUntilIdle()

        assertNotNull(vm.state.value.error)
        assertEquals(2, repo.stored.size)
    }

    @Test
    fun anUnexpectedFailureLeavesTheListIntactAndTellsTheUser() = runTest(dispatcher) {
        val repo = FakeManageCategoryRepository(listOf(food))
        repo.failWith = WalletException.CategoryNotFound(food.id)
        val vm = viewModel(repo)
        vm.delete(food.id)
        dispatcher.scheduler.advanceUntilIdle()

        assertNotNull(vm.state.value.error)
        assertEquals(1, repo.stored.size)
    }
}
