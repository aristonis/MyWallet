package org.aristonis.mywallet.ui.transaction

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.aristonis.mywallet.domain.model.Account
import org.aristonis.mywallet.domain.model.Category
import org.aristonis.mywallet.domain.model.CategoryKind
import org.aristonis.mywallet.domain.model.Money
import org.aristonis.mywallet.domain.model.Transaction
import org.aristonis.mywallet.domain.usecase.RecordExpense
import org.aristonis.mywallet.domain.usecase.RecordIncome
import org.aristonis.mywallet.domain.usecase.RecordTransfer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.aristonis.mywallet.data.format.MoneyParser
import org.aristonis.mywallet.di.TodayProvider
import java.time.LocalDate
import java.util.Locale

/**
 * `subCategoryId` has been stored and mapped since the first schema but never written by a screen.
 * Wiring it up needs three changes that only work together: the picker offers the right children,
 * the edit form seeds the field on load as well as writing it on save, and every reset clears it.
 *
 * The seed-on-load half is the dangerous one. Editing is safe today only because the form never
 * touches the column and `loaded.copy(...)` carries it forward untouched — write the field without
 * seeding it and editing a note alone silently clears the sub-category.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SubCategorySelectionTest {

    private val dispatcher = StandardTestDispatcher()

    @Before fun setUp() = Dispatchers.setMain(dispatcher)

    @After fun tearDown() = Dispatchers.resetMain()

    private val today = LocalDate.of(2026, 2, 1)

    private val food = Category(id = 10, name = "Food", kind = CategoryKind.EXPENSE)
    private val groceries = Category(id = 11, name = "Groceries", kind = CategoryKind.EXPENSE, parentId = 10)
    private val restaurants = Category(id = 12, name = "Restaurants", kind = CategoryKind.EXPENSE, parentId = 10)
    private val transport = Category(id = 13, name = "Transport", kind = CategoryKind.EXPENSE)
    private val salary = Category(id = 20, name = "Salary", kind = CategoryKind.INCOME)

    private fun accounts() = FakeAccountRepository(
        listOf(
            Account(
                id = 1, name = "Cash", typeKey = "cash", currencyCode = "USD",
                openingBalance = Money.of("100", "USD"),
            ),
        ),
    )

    private fun categories() =
        FakeCategoryRepository(listOf(food, groceries, restaurants, transport, salary))

    private fun addViewModel(txs: FakeTransactionRepository): AddTransactionViewModel {
        val accountRepo = accounts()
        val categoryRepo = categories()
        return AddTransactionViewModel(
            accounts = accountRepo,
            categories = categoryRepo,
            recordIncome = RecordIncome(accountRepo, categoryRepo, txs),
            recordExpense = RecordExpense(accountRepo, categoryRepo, txs),
            recordTransfer = RecordTransfer(
                accountRepo,
                FakeCurrencyRepository(),
                FakeRateRepository(),
                FakeSettingsRepository(),
                txs,
            ),
            today = TodayProvider { today },
            moneyParser = MoneyParser(Locale.US),
        )
    }

    @Test
    fun offersOnlyTheChildrenOfTheChosenCategory() = runTest(dispatcher) {
        val vm = addViewModel(FakeTransactionRepository())
        dispatcher.scheduler.advanceUntilIdle()
        vm.selectCategory(food.id)

        assertEquals(listOf("Groceries", "Restaurants"), vm.state.value.subCategoriesForSelected.map { it.name })
    }

    @Test
    fun offersNothingForACategoryWithNoChildren() = runTest(dispatcher) {
        val vm = addViewModel(FakeTransactionRepository())
        dispatcher.scheduler.advanceUntilIdle()
        vm.selectCategory(transport.id)

        assertTrue(vm.state.value.subCategoriesForSelected.isEmpty())
    }

    @Test
    fun changingTheCategoryClearsAnAlreadyChosenSubCategory() = runTest(dispatcher) {
        // Otherwise the previous category's child rides along and the domain guard rejects the save,
        // leaving a Save button that refuses with nothing on screen explaining why.
        val vm = addViewModel(FakeTransactionRepository())
        dispatcher.scheduler.advanceUntilIdle()
        vm.selectCategory(food.id)
        vm.selectSubCategory(groceries.id)
        vm.selectCategory(transport.id)

        assertNull(vm.state.value.selectedSubCategoryId)
    }

    @Test
    fun changingTheTypeClearsTheSubCategory() = runTest(dispatcher) {
        val vm = addViewModel(FakeTransactionRepository())
        dispatcher.scheduler.advanceUntilIdle()
        vm.selectCategory(food.id)
        vm.selectSubCategory(groceries.id)
        vm.selectType(TransactionType.INCOME)

        assertNull(vm.state.value.selectedSubCategoryId)
    }

    @Test
    fun recordsTheChosenSubCategory() = runTest(dispatcher) {
        val txs = FakeTransactionRepository()
        val vm = addViewModel(txs)
        dispatcher.scheduler.advanceUntilIdle()
        vm.selectType(TransactionType.EXPENSE)
        vm.selectAccount(1)
        vm.selectCategory(food.id)
        vm.selectSubCategory(groceries.id)
        vm.setAmount("12.99")
        vm.setDate(today)
        vm.submit()
        dispatcher.scheduler.advanceUntilIdle()

        val saved = txs.added.single() as Transaction.Expense
        assertEquals(food.id, saved.categoryId)
        assertEquals(groceries.id, saved.subCategoryId)
    }

    @Test
    fun savingClearsTheSubCategoryForTheNextEntry() = runTest(dispatcher) {
        // A retained view model would otherwise carry the last entry's child into the next one.
        val txs = FakeTransactionRepository()
        val vm = addViewModel(txs)
        dispatcher.scheduler.advanceUntilIdle()
        vm.selectType(TransactionType.EXPENSE)
        vm.selectAccount(1)
        vm.selectCategory(food.id)
        vm.selectSubCategory(groceries.id)
        vm.setAmount("12.99")
        vm.setDate(today)
        vm.submit()
        dispatcher.scheduler.advanceUntilIdle()
        vm.acknowledgeSaved()

        assertNull(vm.state.value.selectedSubCategoryId)
    }

    @Test
    fun anIncomeCategoryNeverOffersAnExpenseChild() = runTest(dispatcher) {
        // Restored data can hold a child whose kind differs from its parent's; offering it would put
        // a choice on screen that the domain then refuses.
        val accountRepo = accounts()
        val crossKind = FakeCategoryRepository(
            listOf(salary, Category(id = 21, name = "Odd", kind = CategoryKind.EXPENSE, parentId = 20)),
        )
        val txs = FakeTransactionRepository()
        val vm = AddTransactionViewModel(
            accounts = accountRepo,
            categories = crossKind,
            recordIncome = RecordIncome(accountRepo, crossKind, txs),
            recordExpense = RecordExpense(accountRepo, crossKind, txs),
            recordTransfer = RecordTransfer(
                accountRepo,
                FakeCurrencyRepository(),
                FakeRateRepository(),
                FakeSettingsRepository(),
                txs,
            ),
            today = TodayProvider { today },
            moneyParser = MoneyParser(Locale.US),
        )
        dispatcher.scheduler.advanceUntilIdle()
        vm.selectType(TransactionType.INCOME)
        vm.selectCategory(salary.id)

        assertTrue(vm.state.value.subCategoriesForSelected.isEmpty())
    }
}
