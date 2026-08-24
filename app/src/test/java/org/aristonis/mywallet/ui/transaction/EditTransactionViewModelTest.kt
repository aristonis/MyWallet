package org.aristonis.mywallet.ui.transaction

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
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
import org.aristonis.mywallet.domain.usecase.GetAccountBalances
import org.aristonis.mywallet.domain.usecase.RestoreTransaction
import org.aristonis.mywallet.domain.usecase.UpdateTransaction
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.math.BigDecimal
import java.time.LocalDate
import java.util.Locale

/**
 * The bar for editing: load must faithfully re-hydrate the form from a stored transaction, a save must
 * hand the domain a transaction carrying the edited values (proved by reading the row back through the
 * SAME fake repository the view model writes through), and a transfer's account legs must stay locked
 * so an edit can only move amount/date/note. Delete removes the row. The one-shot save/delete signals
 * reset so a re-opened, retained view model never bounces.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class EditTransactionViewModelTest {

    private val dispatcher = StandardTestDispatcher()

    @Before fun setUp() = Dispatchers.setMain(dispatcher)

    @After fun tearDown() = Dispatchers.resetMain()

    private val salary = Category(id = 1, name = "Salary", kind = CategoryKind.INCOME)

    private fun account(id: Long, currency: String = "USD") = Account(
        id = id, name = "acct-$id", typeKey = "cash", currencyCode = currency,
        openingBalance = Money.of("0", currency),
    )

    private class Fixture(
        accounts: List<Account>,
        categories: List<Category>,
        existing: List<Transaction>,
        locale: Locale = Locale.US,
    ) {
        val accountRepo = FakeAccountRepository(accounts)
        val categoryRepo = FakeCategoryRepository(categories)
        val currencyRepo = FakeCurrencyRepository(emptyList())
        val txRepo = FakeTransactionRepository(existing)
        val viewModel = EditTransactionViewModel(
            accounts = accountRepo,
            categories = categoryRepo,
            transactions = txRepo,
            updateTransaction = UpdateTransaction(accountRepo, categoryRepo, currencyRepo, txRepo),
            deleteTransaction = DeleteTransaction(txRepo),
            restoreTransaction = RestoreTransaction(txRepo, categoryRepo),
            moneyParser = MoneyParser(locale),
        )

        /** A live balance probe over the same fakes the view model writes through. */
        val balances get() = GetAccountBalances(accountRepo, txRepo)
    }

    private val income = Transaction.Income(
        id = 5, accountId = 1, amount = Money.of("50", "USD"), categoryId = salary.id,
        date = LocalDate.of(2026, 6, 1), note = "June pay",
    )

    // Same-currency transfer keeps the domain's convert step 1:1, so no currency/rate lookup is needed.
    private val transfer = Transaction.Transfer(
        id = 7, sourceAccountId = 1, destAccountId = 2,
        sourceAmount = Money.of("40", "USD"), destAmount = Money.of("40", "USD"),
        rateUsed = BigDecimal("1"), date = LocalDate.of(2026, 6, 2), note = null,
    )

    private fun incomeFixture() = Fixture(listOf(account(1)), listOf(salary), listOf(income))

    private fun transferFixture() = Fixture(listOf(account(1), account(2)), emptyList(), listOf(transfer))

    @Test
    fun load_populatesForm_fromExistingIncome() = runTest {
        val f = incomeFixture()
        f.viewModel.load(5)
        advanceUntilIdle()

        val s = f.viewModel.state.value
        assertEquals(TransactionType.INCOME, s.type)
        assertEquals(1L, s.selectedAccountId)
        assertEquals(salary.id, s.selectedCategoryId)
        assertEquals("50", s.amountInput)
        assertEquals(LocalDate.of(2026, 6, 1), s.date)
        assertEquals("June pay", s.note)
        assertFalse(s.accountPickersLocked)
    }

    @Test
    fun load_populatesForm_fromExistingTransfer_locksAccountPickers() = runTest {
        val f = transferFixture()
        f.viewModel.load(7)
        advanceUntilIdle()

        val s = f.viewModel.state.value
        assertEquals(TransactionType.TRANSFER, s.type)
        assertEquals(1L, s.selectedAccountId)
        assertEquals(2L, s.destAccountId)
        assertEquals("40", s.amountInput)
        assertTrue(s.accountPickersLocked)
    }

    @Test
    fun editIncomeAmount_callsUpdate_withNewAmount_andSetsSaved() = runTest {
        val f = incomeFixture()
        f.viewModel.load(5)
        advanceUntilIdle()

        f.viewModel.setAmount("75")
        f.viewModel.submit()
        advanceUntilIdle()

        val stored = f.txRepo.findById(5) as Transaction.Income
        assertEquals(Money.of("75", "USD"), stored.amount)
        assertEquals(salary.id, stored.categoryId) // untouched fields survive the edit
        assertTrue(f.viewModel.state.value.saved)
    }

    @Test
    fun editTransfer_preservesLockedAccountsAndRate_onSave() = runTest {
        val f = transferFixture()
        f.viewModel.load(7)
        advanceUntilIdle()

        f.viewModel.setAmount("55")
        f.viewModel.submit()
        advanceUntilIdle()

        val stored = f.txRepo.findById(7) as Transaction.Transfer
        assertEquals(1L, stored.sourceAccountId)
        assertEquals(2L, stored.destAccountId)
        assertEquals(0, BigDecimal("1").compareTo(stored.rateUsed))
        assertEquals(Money.of("55", "USD"), stored.sourceAmount)
        assertEquals(Money.of("55", "USD"), stored.destAmount) // 1:1 same-currency leg
        assertTrue(f.viewModel.state.value.saved)
        assertTrue(f.viewModel.state.value.accountPickersLocked)
    }

    @Test
    fun delete_removesRow_andEntersUndoableState_withoutNavigatingYet() = runTest {
        val f = incomeFixture()
        f.viewModel.load(5)
        advanceUntilIdle()

        f.viewModel.delete()
        advanceUntilIdle()

        assertNull(f.txRepo.findById(5)) // the row is genuinely gone
        assertEquals(5L, f.viewModel.state.value.undoableDelete?.id) // held so an Undo can restore it
        assertFalse(f.viewModel.state.value.deleted) // navigation is deferred until the snackbar resolves
    }

    @Test
    fun undoDelete_reAddsRow_viaRestoreTransaction_andRestoresBalance_thenNavigates() = runTest {
        val f = incomeFixture()
        f.viewModel.load(5)
        advanceUntilIdle()
        assertEquals(Money.of("50", "USD"), f.balances().first().single().balance)

        f.viewModel.delete()
        advanceUntilIdle()
        assertEquals(Money.of("0", "USD"), f.balances().first().single().balance) // effect gone with the row

        f.viewModel.undoDelete()
        advanceUntilIdle()

        assertNotNull(f.txRepo.findById(5)) // the row is back
        assertEquals(Money.of("50", "USD"), f.balances().first().single().balance) // balance reverts
        assertNull(f.viewModel.state.value.undoableDelete)
        assertTrue(f.viewModel.state.value.deleted) // returns to Home, now showing the restored row
    }

    @Test
    fun confirmDelete_afterNoUndo_firesTheOneShotNavigation_andKeepsRowDeleted() = runTest {
        val f = incomeFixture()
        f.viewModel.load(5)
        advanceUntilIdle()
        f.viewModel.delete()
        advanceUntilIdle()

        f.viewModel.confirmDelete() // snackbar dismissed / timed out without an Undo

        assertTrue(f.viewModel.state.value.deleted)
        assertNull(f.viewModel.state.value.undoableDelete)
        assertNull(f.txRepo.findById(5)) // stays deleted
    }

    @Test
    fun submit_whileAnUndoIsPending_doesNothing_soADeletedRowIsNeverSilentlySaved() = runTest {
        val f = incomeFixture()
        f.viewModel.load(5)
        advanceUntilIdle()

        f.viewModel.delete()
        advanceUntilIdle()
        assertNull(f.txRepo.findById(5)) // deleted, undo pending, form still on screen

        f.viewModel.setAmount("999") // user taps the still-visible Save during the undo window
        f.viewModel.submit()
        advanceUntilIdle()

        assertNull(f.txRepo.findById(5)) // still gone — no phantom "update" of a missing row
        assertFalse(f.viewModel.state.value.saved) // and the screen was never told it saved
        assertEquals(5L, f.viewModel.state.value.undoableDelete?.id) // undo is still available
    }

    @Test
    fun acknowledgeSaved_clearsTheOneShotSignal() = runTest {
        val f = incomeFixture()
        f.viewModel.load(5)
        advanceUntilIdle()
        f.viewModel.setAmount("75")
        f.viewModel.submit()
        advanceUntilIdle()
        assertTrue(f.viewModel.state.value.saved)

        f.viewModel.acknowledgeSaved()
        assertFalse(f.viewModel.state.value.saved)
    }

    @Test
    fun acknowledgeDeleted_clearsTheOneShotSignal() = runTest {
        val f = incomeFixture()
        f.viewModel.load(5)
        advanceUntilIdle()
        f.viewModel.delete()
        advanceUntilIdle()
        f.viewModel.confirmDelete()
        assertTrue(f.viewModel.state.value.deleted)

        f.viewModel.acknowledgeDeleted()
        assertFalse(f.viewModel.state.value.deleted)
    }

    // --- reused (retained) view model must never show or write the previously loaded transaction ---

    @Test
    fun loadingAMissingIdAfterAValidOne_clearsTheForm_andSetsError() = runTest {
        val f = incomeFixture()
        f.viewModel.load(5)
        advanceUntilIdle()
        assertEquals(5L, f.viewModel.state.value.loaded?.id)

        f.viewModel.load(999) // deleted since the list was shown
        advanceUntilIdle()

        val s = f.viewModel.state.value
        assertNull(s.loaded) // the stale row is cleared, not left on screen
        assertNotNull(s.error)
    }

    @Test
    fun switchingTransactions_showsTheNewOne_notTheStaleForm() = runTest {
        val f = Fixture(listOf(account(1), account(2)), listOf(salary), listOf(income, transfer))
        f.viewModel.load(5)
        advanceUntilIdle()
        f.viewModel.setAmount("999") // dirty the first form

        f.viewModel.load(7)
        advanceUntilIdle()

        val s = f.viewModel.state.value
        assertEquals(TransactionType.TRANSFER, s.type)
        assertEquals(7L, s.loaded?.id)
        assertEquals("40", s.amountInput) // tx7's value, not the leftover 999
    }

    @Test
    fun submit_blankAmount_setsError_andDoesNotPersist() = runTest {
        val f = incomeFixture()
        f.viewModel.load(5)
        advanceUntilIdle()
        f.viewModel.setAmount("   ")
        f.viewModel.submit()
        advanceUntilIdle()

        assertNotNull(f.viewModel.state.value.error)
        assertFalse(f.viewModel.state.value.saved)
        assertEquals(Money.of("50", "USD"), (f.txRepo.findById(5) as Transaction.Income).amount)
    }

    @Test
    fun submit_withInvalidAccount_setsError_andDoesNotPersist() = runTest {
        val f = incomeFixture()
        f.viewModel.load(5)
        advanceUntilIdle()
        f.accountRepo.delete(1) // account gone → the save must fail loud, not silently drop the edit
        f.viewModel.setAmount("75")
        f.viewModel.submit()
        advanceUntilIdle()

        assertNotNull(f.viewModel.state.value.error)
        assertFalse(f.viewModel.state.value.saved)
        assertEquals(Money.of("50", "USD"), (f.txRepo.findById(5) as Transaction.Income).amount)
    }

    @Test
    fun noOpEditAndSave_inACommaDecimalLocale_preservesTheStoredAmount() = runTest {
        // Regression: the prefill must round-trip through the locale parser (de-DE), not 100x the amount.
        val precise = Transaction.Income(
            id = 9, accountId = 1, amount = Money.of("1000.50", "USD"), categoryId = salary.id,
            date = LocalDate.of(2026, 6, 1), note = "x",
        )
        val f = Fixture(listOf(account(1)), listOf(salary), listOf(precise), locale = Locale.GERMANY)
        f.viewModel.load(9)
        advanceUntilIdle()
        assertEquals("1000,50", f.viewModel.state.value.amountInput) // comma-decimal prefill, round-trippable

        f.viewModel.submit() // no field changed
        advanceUntilIdle()

        assertEquals(Money.of("1000.50", "USD"), (f.txRepo.findById(9) as Transaction.Income).amount)
    }
}
