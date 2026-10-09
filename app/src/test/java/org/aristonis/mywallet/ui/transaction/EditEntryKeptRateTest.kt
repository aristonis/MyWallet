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
import org.aristonis.mywallet.domain.model.ExchangeRate
import org.aristonis.mywallet.domain.model.Money
import org.aristonis.mywallet.domain.model.Settings
import org.aristonis.mywallet.domain.model.Transaction
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import java.math.BigDecimal
import java.time.LocalDate
import java.util.Locale

/**
 * While an edited entry stays in the currency it was recorded in and no rate is typed, the save keeps
 * the rate stored on it. The rate field shows that kept rate, not today's saved one, so the figure on
 * the form is the one the entry goes on holding.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class EditEntryKeptRateTest {

    private val dispatcher = StandardTestDispatcher()

    @Before fun setUp() = Dispatchers.setMain(dispatcher)

    @After fun tearDown() = Dispatchers.resetMain()

    private val food = Category(id = 20, name = "Food", kind = CategoryKind.EXPENSE)
    private val usdAccount = Account(
        id = 1, name = "Cash", typeKey = "cash", currencyCode = "USD",
        openingBalance = Money.of("1000", "USD"),
    )

    private fun expense(rate: BigDecimal?) = Transaction.Expense(
        id = 5, accountId = 1, amount = Money.of("50", "USD"), categoryId = food.id,
        date = LocalDate.of(2026, 6, 1), rateToBase = rate,
    )

    private inner class Fixture(stored: Transaction) {
        private val accountRepo = FakeAccountRepository(listOf(usdAccount))
        private val categoryRepo = FakeCategoryRepository(listOf(food))
        private val currencyRepo = FakeCurrencyRepository(listOf(Currency("USD", "$", 2), Currency("EUR", "€", 2)))
        private val rateRepo = FakeRateRepository(listOf(ExchangeRate("USD", BigDecimal("1.1"))))
        private val settingsRepo = FakeSettingsRepository(Settings(baseCurrencyCode = "EUR"))
        val use = EntryUseCases(accountRepo, categoryRepo, FakeEntryRepository(listOf(stored)), currencyRepo, rateRepo, settingsRepo)
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
            settings = settingsRepo,
        )
        val state get() = viewModel.state.value
    }

    @Test
    fun theFieldShowsTheRateTheEntryKeepsAndTheBaseFigureUsesIt() = runTest {
        val f = Fixture(expense(BigDecimal("1.3")))
        f.viewModel.load(5)
        advanceUntilIdle()

        assertEquals(listOf(RateField("USD", "1.3", isEditable = true)), f.state.rateFields)
        assertEquals("65.00 EUR", f.state.baseEquivalent)
    }

    @Test
    fun savingWithNothingTypedKeepsTheStoredRate() = runTest {
        val f = Fixture(expense(BigDecimal("1.3")))
        f.viewModel.load(5)
        advanceUntilIdle()
        f.viewModel.setAmount("60")

        f.viewModel.submit()
        advanceUntilIdle()

        val stored = f.use.entries.findById(5) as Transaction.Expense
        assertEquals(0, BigDecimal("1.3").compareTo(stored.rateToBase))
    }

    @Test
    fun anEntryWithNoRateShowsAnEmptyFieldAndNoBaseFigure() = runTest {
        val f = Fixture(expense(rate = null))
        f.viewModel.load(5)
        advanceUntilIdle()

        assertEquals(listOf(RateField("USD", "", isEditable = true)), f.state.rateFields)
        assertNull(f.state.baseEquivalent)
    }
}
