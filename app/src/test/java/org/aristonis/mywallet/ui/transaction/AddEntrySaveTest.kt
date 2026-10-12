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
import org.aristonis.mywallet.di.TodayProvider
import org.aristonis.mywallet.domain.model.Account
import org.aristonis.mywallet.domain.model.Category
import org.aristonis.mywallet.domain.model.CategoryKind
import org.aristonis.mywallet.domain.model.Currency
import org.aristonis.mywallet.domain.model.ExchangeRate
import org.aristonis.mywallet.domain.model.Money
import org.aristonis.mywallet.domain.model.Settings
import org.aristonis.mywallet.domain.model.Transaction
import org.aristonis.mywallet.ui.message.UiMessage
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.math.BigDecimal
import java.time.LocalDate
import java.util.Locale

/**
 * The add form saves through SaveEntry. It hands over the rates exactly as typed, tells the screen
 * which date the entry was stored under, and shows a refusal from the save as a message while
 * nothing navigates and nothing is kept.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AddEntrySaveTest {

    private val dispatcher = StandardTestDispatcher()

    @Before fun setUp() = Dispatchers.setMain(dispatcher)

    @After fun tearDown() = Dispatchers.resetMain()

    private val today = LocalDate.of(2026, 10, 8)
    private val currencies = listOf(Currency("USD", "$", 2), Currency("EUR", "€", 2), Currency("SYP", "£S", 2))
    private val salary = Category(id = 10, name = "Salary", kind = CategoryKind.INCOME)
    private val food = Category(id = 20, name = "Food", kind = CategoryKind.EXPENSE)

    private fun account(id: Long, currency: String) = Account(
        id = id, name = "acct-$id", typeKey = "cash", currencyCode = currency,
        openingBalance = Money.of("1000", currency),
    )

    private inner class Fixture(
        rates: List<ExchangeRate> = listOf(ExchangeRate("EUR", BigDecimal("1.1"))),
        saveRates: Boolean = true,
    ) {
        val accountRepo = FakeAccountRepository(listOf(account(1, "USD"), account(2, "EUR"), account(3, "USD")))
        val categoryRepo = FakeCategoryRepository(listOf(salary, food))
        val currencyRepo = FakeCurrencyRepository(currencies)
        val rateRepo = FakeRateRepository(rates)
        val settingsRepo = FakeSettingsRepository(Settings(baseCurrencyCode = "USD", saveTransactionRates = saveRates))
        val use = EntryUseCases(accountRepo, categoryRepo, FakeEntryRepository(), currencyRepo, rateRepo, settingsRepo)
        val viewModel = AddTransactionViewModel(
            accounts = accountRepo,
            categories = categoryRepo,
            saveEntry = use.saveEntry,
            today = TodayProvider { today },
            moneyParser = MoneyParser(Locale.US),
            fx = FakeFxRepository(currencyRepo, rateRepo, settingsRepo),
            settings = settingsRepo,
            moneyFormatter = MoneyFormatter(Locale.US),
            suggestCounterparties = org.aristonis.mywallet.domain.usecase.SuggestCounterparties(use.debts),
            deductionItems = FakeDeductionItemRepository(),
        )
        val state get() = viewModel.state.value

        fun fillExpense(accountId: Long, amount: String) {
            viewModel.selectType(TransactionType.EXPENSE)
            viewModel.selectAccount(accountId)
            viewModel.selectCategory(food.id)
            viewModel.setAmount(amount)
        }

        suspend fun savedRate(code: String) = rateRepo.findByCode(code)?.rateToBase
    }

    @Test
    fun savedOnIsTheDateSaveEntryStored() = runTest {
        val f = Fixture()
        advanceUntilIdle()
        assertNull(f.state.savedOn)
        f.fillExpense(accountId = 1, amount = "12")
        f.viewModel.setDate(LocalDate.of(2026, 5, 20))

        f.viewModel.submit()
        advanceUntilIdle()

        val stored = f.use.entries.stored.single()
        assertEquals(LocalDate.of(2026, 5, 20), stored.date)
        assertEquals(stored.date, f.state.savedOn)
    }

    @Test
    fun acknowledgingTheSaveClearsSavedOnAndResetsTheDateToToday() = runTest {
        val f = Fixture()
        advanceUntilIdle()
        f.fillExpense(accountId = 1, amount = "12")
        f.viewModel.setDate(LocalDate.of(2026, 5, 20))
        f.viewModel.submit()
        advanceUntilIdle()

        f.viewModel.acknowledgeSaved()
        advanceUntilIdle()

        assertNull(f.state.savedOn)
        assertEquals(today, f.state.date)
        assertEquals("", f.state.amountInput)
    }

    @Test
    fun anEntryInTheBaseCurrencyIsStoredThroughSaveEntry() = runTest {
        val f = Fixture()
        advanceUntilIdle()
        f.fillExpense(accountId = 1, amount = "30")

        f.viewModel.submit()
        advanceUntilIdle()

        val stored = f.use.entries.stored.single() as Transaction.Expense
        assertEquals(Money.of("30", "USD"), stored.amount)
        assertEquals(0, BigDecimal.ONE.compareTo(stored.rateToBase))
        assertFalse(f.state.isSubmitting)
    }

    @Test
    fun aCategoryOfTheWrongKindShowsItsMessageAndNothingIsSaved() = runTest {
        val f = Fixture()
        advanceUntilIdle()
        f.viewModel.selectType(TransactionType.EXPENSE)
        f.viewModel.selectAccount(1)
        f.viewModel.selectCategory(salary.id) // an income category on an expense
        f.viewModel.setAmount("12")

        f.viewModel.submit()
        advanceUntilIdle()

        assertEquals(UiMessage.CategoryWrongKind, f.state.error)
        assertNull(f.state.savedOn)
        assertFalse(f.state.isSubmitting)
        assertTrue(f.use.entries.stored.isEmpty())
    }

    @Test
    fun aTransferWithNoRateShowsTheMissingRateMessageAndNothingIsSaved() = runTest {
        val f = Fixture(rates = emptyList())
        advanceUntilIdle()
        f.viewModel.selectType(TransactionType.TRANSFER)
        f.viewModel.selectAccount(1)
        f.viewModel.selectDestAccount(2)
        f.viewModel.setAmount("11")

        f.viewModel.submit()
        advanceUntilIdle()

        assertTrue("was ${f.state.error}", f.state.error is UiMessage.MissingRate)
        assertNull(f.state.savedOn)
        assertTrue(f.use.entries.stored.isEmpty())
    }

    @Test
    fun aRateTypedForAnEuroExpenseIsStampedOnTheEntryAsTyped() = runTest {
        val f = Fixture(saveRates = false)
        advanceUntilIdle()
        f.fillExpense(accountId = 2, amount = "10")
        f.viewModel.setRate("EUR", "1.25")

        f.viewModel.submit()
        advanceUntilIdle()

        val stored = f.use.entries.stored.single() as Transaction.Expense
        assertEquals(0, BigDecimal("1.25").compareTo(stored.rateToBase))
    }

    @Test
    fun withSavingOffATypedRateIsNotKeptForLaterEntries() = runTest {
        val f = Fixture(saveRates = false)
        advanceUntilIdle()
        f.fillExpense(accountId = 2, amount = "10")
        f.viewModel.setRate("EUR", "1.25")

        f.viewModel.submit()
        advanceUntilIdle()

        assertEquals(0, BigDecimal("1.1").compareTo(f.savedRate("EUR")))
        assertTrue(f.use.ledger.filledRates.isEmpty())
    }

    @Test
    fun withSavingOnATypedRateBecomesTheSavedRate() = runTest {
        val f = Fixture(saveRates = true)
        advanceUntilIdle()
        f.fillExpense(accountId = 2, amount = "10")
        f.viewModel.setRate("EUR", "1.25")

        f.viewModel.submit()
        advanceUntilIdle()

        assertEquals(0, BigDecimal("1.25").compareTo(f.savedRate("EUR")))
        assertEquals(listOf("EUR"), f.use.ledger.filledRates.map { it.first })
    }

    @Test
    fun anUntouchedRateFieldIsNotPassedAsTyped() = runTest {
        val f = Fixture(saveRates = true)
        advanceUntilIdle()
        f.fillExpense(accountId = 2, amount = "10") // the field shows the saved 1.1 but nothing is typed

        f.viewModel.submit()
        advanceUntilIdle()

        assertTrue(f.use.ledger.filledRates.isEmpty())
        val stored = f.use.entries.stored.single() as Transaction.Expense
        assertEquals(0, BigDecimal("1.1").compareTo(stored.rateToBase))
    }

    @Test
    fun aCrossCurrencyTransferStoresTheDestinationAtTheTypedRate() = runTest {
        val f = Fixture(saveRates = true)
        advanceUntilIdle()
        f.viewModel.selectType(TransactionType.TRANSFER)
        f.viewModel.selectAccount(1)
        f.viewModel.selectDestAccount(2)
        f.viewModel.setAmount("11")
        f.viewModel.setRate("EUR", "1.1")

        f.viewModel.submit()
        advanceUntilIdle()

        val stored = f.use.entries.stored.single() as Transaction.Transfer
        assertEquals(Money.of("10.00", "EUR"), stored.destAmount)
        assertEquals(stored.date, f.state.savedOn)
    }

    @Test
    fun typingTheSavedRateAgainLeavesTheSavedRateAsItWas() = runTest {
        val f = Fixture(saveRates = true)
        advanceUntilIdle()
        f.fillExpense(accountId = 2, amount = "10")
        f.viewModel.setRate("EUR", "1.1")

        f.viewModel.submit()
        advanceUntilIdle()

        assertTrue(f.rateRepo.upserts.isEmpty())
        assertTrue(f.use.ledger.filledRates.isEmpty())
        assertEquals(0, BigDecimal("1.1").compareTo(f.savedRate("EUR")))
        val stored = f.use.entries.stored.single() as Transaction.Expense
        assertEquals(0, BigDecimal("1.1").compareTo(stored.rateToBase))
    }

    @Test
    fun aClearedRateFieldCountsAsUntouchedSoTheSavedRateIsUsed() = runTest {
        val f = Fixture(saveRates = true)
        advanceUntilIdle()
        f.fillExpense(accountId = 2, amount = "10")
        f.viewModel.setRate("EUR", "")

        f.viewModel.submit()
        advanceUntilIdle()

        assertNull(f.state.error)
        assertEquals(today, f.state.savedOn)
        val stored = f.use.entries.stored.single() as Transaction.Expense
        assertEquals(0, BigDecimal("1.1").compareTo(stored.rateToBase))
        assertTrue(f.rateRepo.upserts.isEmpty())
    }

    @Test
    fun submittingTwiceBeforeTheSaveCompletesStoresOneEntry() = runTest {
        val f = Fixture()
        advanceUntilIdle()
        f.fillExpense(accountId = 1, amount = "12")

        f.viewModel.submit()
        f.viewModel.submit()
        advanceUntilIdle()

        assertEquals(1, f.use.entries.inserts)
        assertEquals(1, f.use.entries.stored.size)
    }

    @Test
    fun submittingAgainAfterTheSaveCompletedBeforeAcknowledgingStoresNothingMore() = runTest {
        val f = Fixture()
        advanceUntilIdle()
        f.fillExpense(accountId = 1, amount = "12")
        f.viewModel.submit()
        advanceUntilIdle()

        f.viewModel.submit()
        advanceUntilIdle()

        assertEquals(1, f.use.entries.inserts)
        assertEquals(1, f.use.entries.stored.size)
    }

    @Test
    fun saveIsNotOfferedWhileASavedDateIsWaitingToBeAcknowledged() = runTest {
        val f = Fixture()
        advanceUntilIdle()
        f.fillExpense(accountId = 1, amount = "12")
        assertTrue(f.state.canSubmit)
        f.viewModel.submit()
        advanceUntilIdle()

        assertEquals(today, f.state.savedOn)
        assertFalse(f.state.canSubmit)
    }

    @Test
    fun savedOnIsTheDateThatWasSubmittedEvenIfTheFormDateChangesBeforeTheSaveCompletes() = runTest {
        val f = Fixture()
        advanceUntilIdle()
        f.fillExpense(accountId = 1, amount = "12")
        f.viewModel.setDate(LocalDate.of(2026, 5, 20))

        f.viewModel.submit()
        f.viewModel.setDate(LocalDate.of(2026, 9, 1))
        advanceUntilIdle()

        assertEquals(LocalDate.of(2026, 5, 20), f.use.entries.stored.single().date)
        assertEquals(LocalDate.of(2026, 5, 20), f.state.savedOn)
    }

    @Test
    fun anUnexpectedFailureInTheSaveShowsAMessageAndFreesTheForm() = runTest {
        val f = Fixture()
        advanceUntilIdle()
        f.fillExpense(accountId = 1, amount = "12")
        f.use.entries.failWith = IllegalStateException("the database is gone")

        f.viewModel.submit()
        advanceUntilIdle()

        assertEquals(UiMessage.Unexpected, f.state.error)
        assertFalse(f.state.isSubmitting)
        assertNull(f.state.savedOn)
        assertTrue(f.state.canSubmit)
    }
}
