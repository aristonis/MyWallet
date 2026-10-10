package org.aristonis.mywallet.ui.transaction

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.aristonis.mywallet.data.format.MoneyFormatter
import org.aristonis.mywallet.data.format.MoneyParser
import org.aristonis.mywallet.domain.model.Account
import org.aristonis.mywallet.domain.model.Category
import org.aristonis.mywallet.domain.model.CategoryKind
import org.aristonis.mywallet.domain.model.Currency
import org.aristonis.mywallet.domain.model.Money
import org.aristonis.mywallet.domain.model.Settings
import org.aristonis.mywallet.domain.model.Transaction
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
 * Deleting an entry goes through DeleteEntry and its undo through RestoreEntries. The entry comes
 * back at its own id with the rate it was recorded with, and an undo whose id is already back in
 * place is simply done.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class EditEntryUndoTest {

    private val dispatcher = StandardTestDispatcher()

    @Before fun setUp() = Dispatchers.setMain(dispatcher)

    @After fun tearDown() = Dispatchers.resetMain()

    private val food = Category(id = 20, name = "Food", kind = CategoryKind.EXPENSE)
    private val usd = Account(
        id = 1, name = "Cash", typeKey = "cash", currencyCode = "USD",
        openingBalance = Money.of("1000", "USD"),
    )
    private val expense = Transaction.Expense(
        id = 5, accountId = 1, amount = Money.of("50", "USD"), categoryId = food.id,
        date = LocalDate.of(2026, 6, 1), note = "lunch", rateToBase = BigDecimal("1.3"),
    )

    private inner class Fixture {
        val accountRepo = FakeAccountRepository(listOf(usd))
        val categoryRepo = FakeCategoryRepository(listOf(food))
        val currencyRepo = FakeCurrencyRepository(listOf(Currency("USD", "$", 2)))
        val rateRepo = FakeRateRepository()
        val settingsRepo = FakeSettingsRepository(Settings(baseCurrencyCode = "EUR"))
        val use = EntryUseCases(accountRepo, categoryRepo, FakeEntryRepository(listOf(expense)), currencyRepo, rateRepo, settingsRepo)
        val viewModel = EditTransactionViewModel(
            accounts = accountRepo,
            categories = categoryRepo,
            entries = use.entries,
            saveEntry = use.saveEntry,
            deleteEntry = use.deleteEntry,
            restoreEntries = use.restoreEntries,
            moneyParser = MoneyParser(Locale.US),
            moneyFormatter = MoneyFormatter(Locale.US),
            fx = FakeFxRepository(currencyRepo, rateRepo, settingsRepo),
            suggestCounterparties = org.aristonis.mywallet.domain.usecase.SuggestCounterparties(use.debts),
            debts = use.debts,
        )
        val state get() = viewModel.state.value
    }

    private suspend fun kotlinx.coroutines.test.TestScope.deleted(): Fixture {
        val f = Fixture()
        f.viewModel.load(5)
        advanceUntilIdle()
        f.viewModel.delete()
        advanceUntilIdle()
        return f
    }

    @Test
    fun deleteRemovesTheEntryAndKeepsItUndoableWithoutLeavingYet() = runTest {
        val f = deleted()

        assertNull(f.use.entries.findById(5))
        assertNotNull(f.state.undoableDelete)
        assertFalse(f.state.deleted)
        assertNull(f.state.error)
    }

    @Test
    fun undoReInsertsTheEntryAtTheSameIdWithItsRecordedRate() = runTest {
        val f = deleted()

        f.viewModel.undoDelete()
        advanceUntilIdle()

        assertEquals(expense, f.use.entries.findById(5))
        assertEquals(1, f.use.entries.stored.size)
        assertNull(f.state.undoableDelete)
        assertTrue(f.state.deleted)
        assertNull(f.state.error)
    }

    @Test
    fun anUndoThatRunsTwiceIsTreatedAsAlreadyUndone() = runTest {
        val f = deleted()
        // The first undo already put the entry back at id 5; this one meets its own entry there.
        val alreadyBack = expense.copy(note = "put back by the first undo")
        f.use.entries.insert(alreadyBack)

        f.viewModel.undoDelete()
        advanceUntilIdle()

        assertNull(f.state.error)
        assertTrue(f.state.deleted)
        assertNull(f.state.undoableDelete)
        assertEquals(alreadyBack, f.use.entries.findById(5)) // nothing was overwritten
        assertEquals(1, f.use.entries.stored.size)
    }

    @Test
    fun confirmingTheDeleteLeavesTheEntryGone() = runTest {
        val f = deleted()

        f.viewModel.confirmDelete()

        assertTrue(f.state.deleted)
        assertNull(f.state.undoableDelete)
        assertNull(f.use.entries.findById(5))
    }
}
