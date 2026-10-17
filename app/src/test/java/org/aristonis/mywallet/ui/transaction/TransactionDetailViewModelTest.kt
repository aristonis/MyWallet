package org.aristonis.mywallet.ui.transaction

import androidx.lifecycle.SavedStateHandle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.aristonis.mywallet.RecordingErrorReporter
import org.aristonis.mywallet.data.format.MoneyFormatter
import org.aristonis.mywallet.domain.error.WalletException
import org.aristonis.mywallet.domain.model.Account
import org.aristonis.mywallet.domain.model.Category
import org.aristonis.mywallet.domain.model.CategoryKind
import org.aristonis.mywallet.domain.model.Currency
import org.aristonis.mywallet.domain.model.DateRange
import org.aristonis.mywallet.domain.model.Debt
import org.aristonis.mywallet.domain.model.DebtSide
import org.aristonis.mywallet.domain.model.DeductionLine
import org.aristonis.mywallet.domain.model.DeductionMode
import org.aristonis.mywallet.domain.model.DeductionTiming
import org.aristonis.mywallet.domain.model.Money
import org.aristonis.mywallet.domain.model.Settings
import org.aristonis.mywallet.domain.model.Transaction
import org.aristonis.mywallet.domain.port.CurrencyRepository
import org.aristonis.mywallet.domain.port.EntryKey
import org.aristonis.mywallet.domain.port.EntryRepository
import org.aristonis.mywallet.ui.label
import org.aristonis.mywallet.ui.message.UiMessage
import org.aristonis.mywallet.ui.navigation.ARG_ID
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
 * The read-only transaction page reads one saved entry as the list's own row, and deletes it with
 * the editor's rules: the debt guard can refuse, an undo puts the entry back, and the page only
 * closes once the delete is confirmed.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class TransactionDetailViewModelTest {

    private val dispatcher = StandardTestDispatcher()

    @Before fun setUp() = Dispatchers.setMain(dispatcher)

    @After fun tearDown() = Dispatchers.resetMain()

    private val day = LocalDate.of(2026, 6, 1)
    private fun usd(amount: String) = Money.of(amount, "USD")
    private fun eur(amount: String) = Money.of(amount, "EUR")

    private val cash = Account(id = 1, name = "Cash", typeKey = "cash", currencyCode = "USD", openingBalance = usd("0"))
    private val euro = Account(id = 2, name = "Euro wallet", typeKey = "cash", currencyCode = "EUR", openingBalance = eur("0"))
    private val food = Category(id = 20, name = "Food", kind = CategoryKind.EXPENSE)
    private val salary = Category(id = 10, name = "Salary", kind = CategoryKind.INCOME)
    private val costs = Category(id = 9, name = "Costs", kind = CategoryKind.EXPENSE)

    private val expense = Transaction.Expense(
        id = 5, accountId = 1, amount = usd("50"), categoryId = food.id, date = day,
        note = "lunch", rateToBase = BigDecimal("1.3"),
    )

    /** An entry that reads fail for, as a stored row the app cannot decode would. */
    private class UnreadableEntries(private val inner: EntryRepository) : EntryRepository by inner {
        var unreadable = true
        private fun failure() = WalletException.UnreadableRow(5, "mystery")

        override suspend fun findById(id: Long): Transaction? {
            if (unreadable) throw failure()
            return inner.findById(id)
        }

        override fun observeAll(): Flow<List<Transaction>> =
            if (unreadable) flow { throw failure() } else inner.observeAll()

        override fun observeBetween(range: DateRange): Flow<List<Transaction>> =
            if (unreadable) flow { throw failure() } else inner.observeBetween(range)

        override fun observePage(range: DateRange, after: EntryKey?, limit: Int): Flow<List<Transaction>> =
            if (unreadable) flow { throw failure() } else inner.observePage(range, after, limit)
    }

    /** Currencies that cannot be read, so rows cannot be formatted. */
    private class BrokenCurrencies : CurrencyRepository {
        override fun observeAll(): Flow<List<Currency>> = flow { throw IllegalStateException("currencies gone") }
        override suspend fun findByCode(code: String): Currency? = throw IllegalStateException("currencies gone")
    }

    private inner class Fixture(
        existing: List<Transaction>,
        entryId: Long = 5,
        debts: List<Debt> = emptyList(),
        brokenCurrencies: Boolean = false,
        unreadable: Boolean = false,
    ) {
        val accountRepo = FakeAccountRepository(listOf(cash, euro))
        val categoryRepo = FakeCategoryRepository(listOf(food, salary, costs))
        val currencyRepo = FakeCurrencyRepository(listOf(Currency("USD", "$", 2), Currency("EUR", "€", 2)))
        val rateRepo = FakeRateRepository()
        val settingsRepo = FakeSettingsRepository(Settings(baseCurrencyCode = "EUR"))
        val use = EntryUseCases(
            accountRepo, categoryRepo, FakeEntryRepository(existing), currencyRepo, rateRepo, settingsRepo,
            FakeDebtRepository(debts),
        )
        val errors = RecordingErrorReporter()
        val readable = UnreadableEntries(use.entries).also { it.unreadable = unreadable }
        val viewModel = TransactionDetailViewModel(
            debts = FakeDebtRepository(),
            accounts = accountRepo,
            categories = categoryRepo,
            currencies = if (brokenCurrencies) BrokenCurrencies() else currencyRepo,
            settings = settingsRepo,
            entries = readable,
            deleteEntry = use.deleteEntry,
            restoreEntries = use.restoreEntries,
            moneyFormatter = MoneyFormatter(Locale.US),
            savedStateHandle = SavedStateHandle(mapOf(ARG_ID to entryId)),
            defaultDispatcher = dispatcher,
            errors = errors,
        )
        val state get() = viewModel.state.value

        /** The row exactly as the transactions list would show this entry. */
        fun listRow(entry: Transaction): TransactionRow = entry.toRow(
            RowLookups(
                accountsById = mapOf(cash.id to cash, euro.id to euro),
                categoriesById = listOf(food, salary, costs).associateBy { it.id },
                currencies = listOf(Currency("USD", "$", 2), Currency("EUR", "€", 2)),
                baseCurrencyCode = "EUR",
            ),
            MoneyFormatter(Locale.US),
        )
    }

    private fun TestScope.opened(f: Fixture): Fixture {
        backgroundScope.launch { f.viewModel.state.collect {} }
        return f
    }

    private fun TestScope.loaded(f: Fixture): Fixture {
        opened(f)
        advanceUntilIdle()
        return f
    }

    private val aLoan = Transaction.Borrow(id = 1, accountId = 1, debtId = 7, amount = usd("5000"), date = day, note = "loan")
    private val aRepayment = Transaction.Repayment(
        id = 20, side = DebtSide.I_OWE, accountId = 1, debtId = 7,
        accountAmount = usd("5000"), principal = usd("5000"), date = day,
    )
    private val ali = Debt(7, "Ali", DebtSide.I_OWE, "USD")

    @Test
    fun anExpenseShowsTheListRowWithAccountCategoryAndNote() = runTest {
        val f = loaded(Fixture(listOf(expense)))

        val row = requireNotNull(f.state.row)
        assertEquals(f.listRow(expense), row)
        assertEquals("Cash", row.accountName)
        assertEquals(food.label(), row.categoryLabel)
        assertEquals("lunch", row.note)
        assertFalse(f.state.isLoading)
        assertFalse(f.state.notFound)
        assertNull(f.state.loadFailure)
    }

    @Test
    fun anIncomeCarriesTheSavedDeductionSnapshotsAsStored() = runTest {
        val tax = DeductionLine(3, 0, "Tax (old name)", DeductionMode.FIXED, BigDecimal("50"), "USD", DeductionTiming.NOW, costs.id, usd("50"))
        val pension = DeductionLine(4, 1, "Pension", DeductionMode.PERCENT, BigDecimal("4"), null, DeductionTiming.LATER, costs.id, usd("40"))
        val income = Transaction.Income(
            id = 5, accountId = 1, amount = usd("1000"), categoryId = salary.id, date = day,
            deductions = listOf(tax, pension),
        )
        // No deduction catalog is wired in at all: whatever it says today cannot change the page.
        val f = loaded(Fixture(listOf(income)))

        val row = requireNotNull(f.state.row)
        assertEquals(listOf("Tax (old name)", "Pension"), row.deductions.map { it.name })
        assertEquals(listOf(usd("50"), usd("40")), row.deductions.map { it.amount })
        assertEquals(f.listRow(income), row)
        assertEquals(usd("1000"), row.grossAmount)
        assertEquals(usd("950"), row.accountMovement)
    }

    @Test
    fun aTransferShowsBothAccountsAndBothAmounts() = runTest {
        val transfer = Transaction.Transfer(
            id = 5, sourceAccountId = 1, destAccountId = 2, sourceAmount = usd("100"),
            destAmount = eur("90"), rateUsed = BigDecimal("0.9"), date = day,
        )
        val f = loaded(Fixture(listOf(transfer)))

        val row = requireNotNull(f.state.row)
        assertEquals("Cash", row.accountName)
        assertEquals("Euro wallet", row.destAccountName)
        assertEquals(usd("100"), row.amount)
        assertEquals(eur("90"), row.destAmount)
        assertEquals(f.listRow(transfer), row)
    }

    @Test
    fun anUnknownEntryIdIsNotFoundAndNotAFailure() = runTest {
        val f = loaded(Fixture(listOf(expense), entryId = 99))

        assertTrue(f.state.notFound)
        assertNull(f.state.row)
        assertFalse(f.state.isLoading)
        assertNull(f.state.loadFailure)
    }

    @Test
    fun anEntryDeletedInTheEditorClosesThePageWhenItIsShownAgain() = runTest {
        val f = loaded(Fixture(listOf(expense)))
        f.use.entries.delete(5)

        f.viewModel.refresh()
        advanceUntilIdle()

        assertTrue(f.state.deleted)
        assertNull(f.state.row)
        assertFalse(f.state.notFound)
    }

    @Test
    fun anUnreadableEntryIsALoadFailureWithNoRow() = runTest {
        val f = loaded(Fixture(listOf(expense), unreadable = true))

        assertNotNull(f.state.loadFailure)
        assertNull(f.state.row)
        assertFalse(f.state.notFound)
        assertFalse(f.state.isLoading)
    }

    @Test
    fun retryShowsLoadingThenReadsTheEntryAgain() = runTest {
        val f = loaded(Fixture(listOf(expense), unreadable = true))
        f.readable.unreadable = false

        f.viewModel.retry()
        assertTrue(f.state.isLoading)
        assertNull(f.state.loadFailure)
        advanceUntilIdle()

        assertFalse(f.state.isLoading)
        assertNull(f.state.loadFailure)
        assertEquals(f.listRow(expense), f.state.row)
    }

    @Test
    fun deleteKeepsTheRowVisibleAndTheEntryUndoableUntilConfirmed() = runTest {
        val f = loaded(Fixture(listOf(expense)))

        f.viewModel.delete()
        advanceUntilIdle()

        assertNull(f.use.entries.findById(5))
        assertNotNull(f.state.row)
        assertNotNull(f.state.undoableDelete)
        assertFalse(f.state.deleted)
        assertFalse(f.state.isDeleting)
        assertNull(f.state.deleteError)
    }

    @Test
    fun confirmingTheDeleteLetsThePageClose() = runTest {
        val f = loaded(Fixture(listOf(expense)))
        f.viewModel.delete()
        advanceUntilIdle()

        f.viewModel.confirmDelete()
        advanceUntilIdle()

        assertTrue(f.state.deleted)
        assertNull(f.state.undoableDelete)
        assertNull(f.use.entries.findById(5))

        f.viewModel.acknowledgeDeleted()
        advanceUntilIdle()
        assertFalse(f.state.deleted)
    }

    @Test
    fun undoingTheDeleteRestoresTheEntryWithoutClosingThePage() = runTest {
        val f = loaded(Fixture(listOf(expense)))
        f.viewModel.delete()
        advanceUntilIdle()

        f.viewModel.undoDelete()
        advanceUntilIdle()

        assertEquals(expense, f.use.entries.findById(5))
        assertNull(f.state.undoableDelete)
        assertFalse(f.state.deleted)
        assertNull(f.state.deleteError)
    }

    @Test
    fun aFailedRestoreIsReportedAndTheEntryStaysUndoable() = runTest {
        val f = loaded(Fixture(listOf(expense)))
        f.viewModel.delete()
        advanceUntilIdle()
        f.use.entries.beforeInsert = { error("disk gone") }

        f.viewModel.undoDelete()
        advanceUntilIdle()

        assertTrue(f.errors.reported.isNotEmpty())
        assertNotNull(f.state.undoableDelete)
        assertFalse(f.state.deleted)
        assertNull(f.use.entries.findById(5))
    }

    @Test
    fun aDeleteTheDebtGuardRefusesShowsTheErrorAndKeepsTheEntry() = runTest {
        val f = loaded(Fixture(listOf(aLoan, aRepayment), entryId = 1, debts = listOf(ali)))

        f.viewModel.delete()
        advanceUntilIdle()

        assertEquals(UiMessage.DebtBelowZero("Ali", usd("5000")), f.state.deleteError)
        assertNotNull(f.state.row)
        assertNull(f.state.undoableDelete)
        assertFalse(f.state.isDeleting)
        assertFalse(f.state.deleted)
        assertEquals(listOf(aLoan, aRepayment), f.use.entries.stored)
    }

    @Test
    fun deleteIsIgnoredWhileTheEntryIsStillLoading() = runTest {
        val f = opened(Fixture(listOf(expense)))

        f.viewModel.delete()
        advanceUntilIdle()

        assertEquals(listOf(expense), f.use.entries.stored)
        assertNull(f.state.undoableDelete)
        assertNull(f.state.deleteError)
    }

    @Test
    fun deleteIsIgnoredWhenTheEntryWasNotFound() = runTest {
        val f = loaded(Fixture(listOf(expense), entryId = 99))

        f.viewModel.delete()
        advanceUntilIdle()

        assertEquals(listOf(expense), f.use.entries.stored)
        assertNull(f.state.undoableDelete)
        assertNull(f.state.deleteError)
        assertTrue(f.state.notFound)
    }

    @Test
    fun deleteIsIgnoredWhenTheReadFailed() = runTest {
        val f = loaded(Fixture(listOf(expense), unreadable = true))

        f.viewModel.delete()
        advanceUntilIdle()

        assertEquals(listOf(expense), f.use.entries.stored)
        assertNull(f.state.undoableDelete)
        assertNotNull(f.state.loadFailure)
    }

    @Test
    fun aSecondDeleteWhileDeletingDoesNotDeleteTwice() = runTest {
        val f = loaded(Fixture(listOf(expense)))

        f.viewModel.delete()
        f.viewModel.delete()
        advanceUntilIdle()

        // A second delete would meet a missing entry and surface an error instead.
        assertNull(f.state.deleteError)
        assertNotNull(f.state.undoableDelete)
        assertEquals(1, f.state.undoableDelete?.entries?.size)
        assertTrue(f.use.entries.stored.isEmpty())
    }

    @Test
    fun aCurrencyLookupFailureReportsAndShowsALoadFailureInsteadOfCrashing() = runTest {
        val f = loaded(Fixture(listOf(expense), brokenCurrencies = true))

        assertTrue(f.errors.reported.isNotEmpty())
        assertNotNull(f.state.loadFailure)
        assertNull(f.state.row)
        assertFalse(f.state.isLoading)
        assertFalse(f.state.notFound)
    }
}
