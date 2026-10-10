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
import org.aristonis.mywallet.domain.model.DebtSide
import org.aristonis.mywallet.domain.model.Money
import org.aristonis.mywallet.domain.model.Settings
import org.aristonis.mywallet.domain.model.Transaction
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.LocalDate
import java.util.Locale

/** A debt-kind entry opens in the edit view model read-only: it loads, cannot be saved and cannot be deleted. */
@OptIn(ExperimentalCoroutinesApi::class)
class EditDebtKindReadOnlyTest {

    private val dispatcher = StandardTestDispatcher()

    @Before fun setUp() = Dispatchers.setMain(dispatcher)

    @After fun tearDown() = Dispatchers.resetMain()

    private val day = LocalDate.of(2026, 7, 1)
    private fun usd(a: String) = Money.of(a, "USD")

    private val debtKinds: List<Transaction> = listOf(
        Transaction.Borrow(id = 1, accountId = 1, debtId = 7, amount = usd("5000"), date = day, note = "loan"),
        Transaction.Lend(id = 2, accountId = 1, debtId = 8, amount = usd("300"), date = day),
        Transaction.OwedBefore(id = 3, debtId = 9, side = DebtSide.OWED_TO_ME, amount = usd("900"), date = day),
        Transaction.CreditPurchase(id = 4, debtId = 7, amount = usd("120.50"), categoryId = 20, date = day),
        Transaction.EarnedUnpaid(id = 5, debtId = 8, amount = usd("800"), categoryId = 10, date = day),
        Transaction.Repayment(
            id = 6, side = DebtSide.I_OWE, accountId = 1, debtId = 7, accountAmount = usd("450"), principal = usd("400"),
            interest = usd("50"), interestCategoryId = 30, date = day,
        ),
        Transaction.WriteOff(id = 7, side = DebtSide.OWED_TO_ME, debtId = 8, amount = usd("75"), categoryId = 40, date = day),
    )

    private val income = Transaction.Income(id = 8, accountId = 1, amount = usd("50"), categoryId = 10, date = day)

    private class Fixture(existing: List<Transaction>) {
        val accountRepo = FakeAccountRepository(
            listOf(Account(id = 1, name = "Cash", typeKey = "cash", currencyCode = "USD", openingBalance = Money.zero("USD"))),
        )
        val categoryRepo = FakeCategoryRepository(
            listOf(
                Category(id = 10, name = "Salary", kind = CategoryKind.INCOME),
                Category(id = 20, name = "Food", kind = CategoryKind.EXPENSE),
            ),
        )
        val currencyRepo = FakeCurrencyRepository(listOf(Currency("USD", "$", 2)))
        val rateRepo = FakeRateRepository()
        val settingsRepo = FakeSettingsRepository(Settings(baseCurrencyCode = "USD"))
        val use = EntryUseCases(accountRepo, categoryRepo, FakeEntryRepository(existing), currencyRepo, rateRepo, settingsRepo)
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
        )
    }

    @Test
    fun eachDebtKindOpensReadOnlyWithoutAnError() = runTest {
        debtKinds.forEach { entry ->
            val f = Fixture(debtKinds + income)
            advanceUntilIdle()

            f.viewModel.load(entry.id)
            advanceUntilIdle()

            val state = f.viewModel.state.value
            assertEquals(entry.toString(), entry, state.loaded)
            assertTrue(entry.toString(), state.readOnly)
            assertFalse(entry.toString(), state.canSubmit)
            assertNull(entry.toString(), state.error)
            assertEquals(entry.toString(), entry.date, state.date)
            assertEquals(entry.toString(), entry.note.orEmpty(), state.note)
        }
    }

    @Test
    fun submittingAReadOnlyEntryWritesNothing() = runTest {
        debtKinds.forEach { entry ->
            val f = Fixture(debtKinds + income)
            advanceUntilIdle()
            f.viewModel.load(entry.id)
            advanceUntilIdle()
            f.viewModel.setAmount("1")

            f.viewModel.submit()
            advanceUntilIdle()

            val state = f.viewModel.state.value
            assertEquals(entry.toString(), 0, f.use.entries.replaces)
            assertEquals(entry.toString(), 0, f.use.entries.inserts)
            assertNull(entry.toString(), state.savedOn)
            assertFalse(entry.toString(), state.isSubmitting)
            assertEquals(entry.toString(), debtKinds + income, f.use.entries.stored)
        }
    }

    @Test
    fun deletingAReadOnlyEntryLeavesTheStoredEntriesUntouchedAndOffersNoUndo() = runTest {
        debtKinds.forEach { entry ->
            val f = Fixture(debtKinds + income)
            advanceUntilIdle()
            f.viewModel.load(entry.id)
            advanceUntilIdle()

            f.viewModel.delete()
            advanceUntilIdle()

            val state = f.viewModel.state.value
            assertEquals(entry.toString(), debtKinds + income, f.use.entries.stored)
            assertNull(entry.toString(), state.undoableDelete)
            assertFalse(entry.toString(), state.deleted)
            assertFalse(entry.toString(), state.isSubmitting)
            assertNull(entry.toString(), state.error)
            assertEquals(entry.toString(), entry, state.loaded)
        }
    }

    @Test
    fun anIncomeIsNotReadOnlyAndStillSubmittable() = runTest {
        val f = Fixture(debtKinds + income)
        advanceUntilIdle()

        f.viewModel.load(income.id)
        advanceUntilIdle()

        assertFalse(f.viewModel.state.value.readOnly)
        assertTrue(f.viewModel.state.value.canSubmit)
    }

    @Test
    fun reopeningAnIncomeAfterADebtKindLiftsTheReadOnlyFlag() = runTest {
        val f = Fixture(debtKinds + income)
        advanceUntilIdle()
        f.viewModel.load(debtKinds.first().id)
        advanceUntilIdle()

        f.viewModel.load(income.id)
        advanceUntilIdle()

        assertFalse(f.viewModel.state.value.readOnly)
        assertEquals(income, f.viewModel.state.value.loaded)
    }

    @Test
    fun theFlagIsFalseBeforeAnythingIsLoaded() = runTest {
        val f = Fixture(debtKinds)
        advanceUntilIdle()
        assertFalse(f.viewModel.state.value.readOnly)
    }
}
