package org.aristonis.mywallet.ui.transaction

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.aristonis.mywallet.data.format.MoneyParser
import org.aristonis.mywallet.domain.model.Account
import org.aristonis.mywallet.domain.model.Category
import org.aristonis.mywallet.domain.model.CategoryKind
import org.aristonis.mywallet.domain.model.Money
import org.aristonis.mywallet.domain.model.Transaction
import org.aristonis.mywallet.domain.usecase.DeleteTransaction
import org.aristonis.mywallet.domain.usecase.RestoreTransaction
import org.aristonis.mywallet.domain.usecase.UpdateTransaction
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import java.time.LocalDate
import java.util.Locale

/**
 * Editing has been safe only because the form never touched `subCategoryId`: `loaded.copy(...)`
 * carried it forward untouched. Exposing the field breaks that accidental protection, so the load
 * side has to seed it in the same change that the save side writes it.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class EditSubCategoryTest {

    private val dispatcher = StandardTestDispatcher()

    @Before fun setUp() = Dispatchers.setMain(dispatcher)

    @After fun tearDown() = Dispatchers.resetMain()

    private val today = LocalDate.of(2026, 6, 1)

    private val food = Category(id = 10, name = "Food", kind = CategoryKind.EXPENSE)
    private val groceries = Category(id = 11, name = "Groceries", kind = CategoryKind.EXPENSE, parentId = 10)
    private val restaurants = Category(id = 12, name = "Restaurants", kind = CategoryKind.EXPENSE, parentId = 10)

    private val expense = Transaction.Expense(
        id = 5, accountId = 1, amount = Money.of("12.99", "USD"),
        categoryId = food.id, subCategoryId = groceries.id, date = today, note = "shop",
    )

    private fun fixture(existing: Transaction = expense): Pair<EditTransactionViewModel, FakeTransactionRepository> {
        val accountRepo = FakeAccountRepository(
            listOf(
                Account(
                    id = 1, name = "Cash", typeKey = "cash", currencyCode = "USD",
                    openingBalance = Money.of("100", "USD"),
                ),
                Account(
                    id = 2, name = "Bank", typeKey = "bank", currencyCode = "USD",
                    openingBalance = Money.of("0", "USD"),
                ),
            ),
        )
        val categoryRepo = FakeCategoryRepository(listOf(food, groceries, restaurants))
        val currencyRepo = FakeCurrencyRepository(emptyList())
        val txRepo = FakeTransactionRepository(listOf(existing))
        val vm = EditTransactionViewModel(
            accounts = accountRepo,
            categories = categoryRepo,
            transactions = txRepo,
            updateTransaction = UpdateTransaction(accountRepo, categoryRepo, currencyRepo, txRepo),
            deleteTransaction = DeleteTransaction(txRepo),
            restoreTransaction = RestoreTransaction(txRepo, categoryRepo),
            moneyParser = MoneyParser(Locale.US),
        )
        return vm to txRepo
    }

    @Test
    fun loadingSeedsTheStoredSubCategory() = runTest(dispatcher) {
        val (vm, _) = fixture()
        vm.load(expense.id)
        dispatcher.scheduler.advanceUntilIdle()

        assertEquals(groceries.id, vm.state.value.selectedSubCategoryId)
    }

    @Test
    fun editingOnlyTheNotePreservesTheStoredSubCategory() = runTest(dispatcher) {
        // The regression this whole slice can produce: touch nothing but the note and the finer
        // label is silently gone, taking that spend out of every sub-category breakdown.
        val (vm, txRepo) = fixture()
        vm.load(expense.id)
        dispatcher.scheduler.advanceUntilIdle()
        vm.setNote("corner shop")
        vm.submit()
        dispatcher.scheduler.advanceUntilIdle()

        val saved = txRepo.added.single() as Transaction.Expense
        assertEquals("corner shop", saved.note)
        assertEquals(groceries.id, saved.subCategoryId)
        assertEquals(food.id, saved.categoryId)
    }

    @Test
    fun theSubCategoryCanBeChanged() = runTest(dispatcher) {
        val (vm, txRepo) = fixture()
        vm.load(expense.id)
        dispatcher.scheduler.advanceUntilIdle()
        vm.selectSubCategory(restaurants.id)
        vm.submit()
        dispatcher.scheduler.advanceUntilIdle()

        assertEquals(restaurants.id, (txRepo.added.single() as Transaction.Expense).subCategoryId)
    }

    @Test
    fun theSubCategoryCanBeCleared() = runTest(dispatcher) {
        val (vm, txRepo) = fixture()
        vm.load(expense.id)
        dispatcher.scheduler.advanceUntilIdle()
        vm.selectSubCategory(null)
        vm.submit()
        dispatcher.scheduler.advanceUntilIdle()

        assertNull((txRepo.added.single() as Transaction.Expense).subCategoryId)
    }

    @Test
    fun changingTheCategoryClearsTheStoredSubCategory() = runTest(dispatcher) {
        // The child belongs to the old parent, so keeping it would be refused by the domain guard.
        val standalone = Category(id = 13, name = "Transport", kind = CategoryKind.EXPENSE)
        val accountRepo = FakeAccountRepository(
            listOf(
                Account(
                    id = 1, name = "Cash", typeKey = "cash", currencyCode = "USD",
                    openingBalance = Money.of("100", "USD"),
                ),
            ),
        )
        val categoryRepo = FakeCategoryRepository(listOf(food, groceries, restaurants, standalone))
        val currencyRepo = FakeCurrencyRepository(emptyList())
        val txRepo = FakeTransactionRepository(listOf(expense))
        val vm = EditTransactionViewModel(
            accounts = accountRepo,
            categories = categoryRepo,
            transactions = txRepo,
            updateTransaction = UpdateTransaction(accountRepo, categoryRepo, currencyRepo, txRepo),
            deleteTransaction = DeleteTransaction(txRepo),
            restoreTransaction = RestoreTransaction(txRepo, categoryRepo),
            moneyParser = MoneyParser(Locale.US),
        )
        vm.load(expense.id)
        dispatcher.scheduler.advanceUntilIdle()
        vm.selectCategory(standalone.id)

        assertNull(vm.state.value.selectedSubCategoryId)
    }

    @Test
    fun aTransferLoadsWithNoSubCategory() = runTest(dispatcher) {
        val transfer = Transaction.Transfer(
            id = 7, sourceAccountId = 1, destAccountId = 2,
            sourceAmount = Money.of("40", "USD"), destAmount = Money.of("40", "USD"),
            rateUsed = java.math.BigDecimal.ONE, date = today,
        )
        val (vm, _) = fixture(transfer)
        vm.load(transfer.id)
        dispatcher.scheduler.advanceUntilIdle()

        assertNull(vm.state.value.selectedSubCategoryId)
    }
}
