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
 * The edit form saves through SaveEntry. It reports the stored date, shows the rate field for an
 * account in another currency (priced from the saved rates, never from the rate the entry had), and
 * shows a refusal from the save as a message while nothing navigates.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class EditEntrySaveTest {

    private val dispatcher = StandardTestDispatcher()

    @Before fun setUp() = Dispatchers.setMain(dispatcher)

    @After fun tearDown() = Dispatchers.resetMain()

    private val currencies = listOf(Currency("USD", "$", 2), Currency("EUR", "€", 2), Currency("SYP", "£S", 2))
    private val salary = Category(id = 10, name = "Salary", kind = CategoryKind.INCOME)
    private val food = Category(id = 20, name = "Food", kind = CategoryKind.EXPENSE)

    private fun account(id: Long, currency: String) = Account(
        id = id, name = "acct-$id", typeKey = "cash", currencyCode = currency,
        openingBalance = Money.of("1000", currency),
    )

    /** A USD expense recorded at 1.3, an old rate no current saved rate matches. */
    private val expense = Transaction.Expense(
        id = 5, accountId = 1, amount = Money.of("50", "USD"), categoryId = food.id,
        date = LocalDate.of(2026, 6, 1), note = "lunch", rateToBase = BigDecimal("1.3"),
    )

    private inner class Fixture(saveRates: Boolean = true) {
        val accountRepo = FakeAccountRepository(
            listOf(account(1, "USD"), account(2, "SYP"), account(3, "EUR")),
        )
        val categoryRepo = FakeCategoryRepository(listOf(salary, food))
        val currencyRepo = FakeCurrencyRepository(currencies)
        val rateRepo = FakeRateRepository(
            listOf(ExchangeRate("USD", BigDecimal("1.1")), ExchangeRate("SYP", BigDecimal("0.0001"))),
        )
        val settingsRepo = FakeSettingsRepository(Settings(baseCurrencyCode = "EUR", saveTransactionRates = saveRates))
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

        suspend fun storedExpense() = use.entries.findById(5) as Transaction.Expense
    }

    @Test
    fun savedOnIsTheDateSaveEntryStored() = runTest {
        val f = Fixture()
        f.viewModel.load(5)
        advanceUntilIdle()
        assertNull(f.state.savedOn)
        f.viewModel.setDate(LocalDate.of(2026, 7, 14))

        f.viewModel.submit()
        advanceUntilIdle()

        assertEquals(LocalDate.of(2026, 7, 14), f.storedExpense().date)
        assertEquals(f.storedExpense().date, f.state.savedOn)
    }

    @Test
    fun anEditThatKeepsTheDateReportsTheLoadedDate() = runTest {
        val f = Fixture()
        f.viewModel.load(5)
        advanceUntilIdle()

        f.viewModel.setAmount("55")
        f.viewModel.submit()
        advanceUntilIdle()

        assertEquals(LocalDate.of(2026, 6, 1), f.state.savedOn)
    }

    @Test
    fun acknowledgingTheSaveClearsSavedOn() = runTest {
        val f = Fixture()
        f.viewModel.load(5)
        advanceUntilIdle()
        f.viewModel.submit()
        advanceUntilIdle()

        f.viewModel.acknowledgeSaved()

        assertNull(f.state.savedOn)
    }

    @Test
    fun loadingAgainClearsASavedOnLeftBehind() = runTest {
        val f = Fixture()
        f.viewModel.load(5)
        advanceUntilIdle()
        f.viewModel.submit()
        advanceUntilIdle()

        f.viewModel.load(5)

        assertNull(f.state.savedOn)
    }

    @Test
    fun anAccountInAnotherCurrencyShowsTheRateFieldPrefilledFromRateBookWithoutTheOldRate() = runTest {
        val f = Fixture()
        f.viewModel.load(5)
        advanceUntilIdle()

        f.viewModel.selectAccount(2) // SYP; the entry was recorded in USD at 1.3

        assertEquals(listOf(RateField("SYP", "0.0001", isEditable = true)), f.state.rateFields)
        assertTrue(f.state.rateFields.none { it.input.startsWith("1.3") })
    }

    @Test
    fun anAccountInTheBaseCurrencyShowsNoRateField() = runTest {
        val f = Fixture()
        f.viewModel.load(5)
        advanceUntilIdle()

        f.viewModel.selectAccount(3) // EUR is the base

        assertTrue(f.state.rateFields.isEmpty())
    }

    @Test
    fun savingOntoAnotherCurrencyAccountStampsTheSavedRateNotTheOldOne() = runTest {
        val f = Fixture()
        f.viewModel.load(5)
        advanceUntilIdle()
        f.viewModel.selectAccount(2)
        f.viewModel.setAmount("5000")

        f.viewModel.submit()
        advanceUntilIdle()

        val stored = f.storedExpense()
        assertEquals(Money.of("5000", "SYP"), stored.amount)
        assertEquals(0, BigDecimal("0.0001").compareTo(stored.rateToBase))
    }

    @Test
    fun aRateTypedOnTheEditIsPassedAsTypedAndStamped() = runTest {
        val f = Fixture(saveRates = false)
        f.viewModel.load(5)
        advanceUntilIdle()
        f.viewModel.selectAccount(2)
        f.viewModel.setAmount("5000")
        f.viewModel.setRate("SYP", "0.0002")

        f.viewModel.submit()
        advanceUntilIdle()

        assertEquals(0, BigDecimal("0.0002").compareTo(f.storedExpense().rateToBase))
        assertEquals(0, BigDecimal("0.0001").compareTo(f.rateRepo.findByCode("SYP")?.rateToBase))
    }

    @Test
    fun aCategoryOfTheWrongKindShowsItsMessageAndTheStoredEntryIsUntouched() = runTest {
        val f = Fixture()
        f.viewModel.load(5)
        advanceUntilIdle()
        f.viewModel.selectCategory(salary.id) // an income category on an expense

        f.viewModel.submit()
        advanceUntilIdle()

        assertEquals(UiMessage.CategoryWrongKind, f.state.error)
        assertNull(f.state.savedOn)
        assertFalse(f.state.isSubmitting)
        assertEquals(expense, f.use.entries.findById(5))
    }

    private val transfer = Transaction.Transfer(
        id = 7, sourceAccountId = 1, destAccountId = 3,
        sourceAmount = Money.of("100", "USD"), destAmount = Money.of("90", "EUR"),
        rateUsed = BigDecimal("0.9"), date = LocalDate.of(2026, 6, 2),
    )

    @Test
    fun typingTheKeptRateAgainChangesNeitherTheSavedRateNorFillsAnything() = runTest {
        val f = Fixture(saveRates = true)
        f.viewModel.load(5)
        advanceUntilIdle()
        f.viewModel.setRate("USD", "")
        f.viewModel.setRate("USD", "1.3")

        f.viewModel.submit()
        advanceUntilIdle()

        assertNull(f.state.error)
        assertEquals(0, BigDecimal("1.1").compareTo(f.rateRepo.findByCode("USD")?.rateToBase))
        assertTrue(f.rateRepo.upserts.isEmpty())
        assertTrue(f.use.ledger.filledRates.isEmpty())
        assertEquals(0, BigDecimal("1.3").compareTo(f.storedExpense().rateToBase))
    }

    @Test
    fun typingADifferentRateOnTheEditReplacesTheSavedRate() = runTest {
        val f = Fixture(saveRates = true)
        f.viewModel.load(5)
        advanceUntilIdle()
        f.viewModel.setRate("USD", "1.5")

        f.viewModel.submit()
        advanceUntilIdle()

        assertEquals(0, BigDecimal("1.5").compareTo(f.rateRepo.findByCode("USD")?.rateToBase))
        assertEquals(0, BigDecimal("1.5").compareTo(f.storedExpense().rateToBase))
    }

    @Test
    fun aClearedRateFieldCountsAsUntouchedSoTheEntryKeepsItsStoredRate() = runTest {
        val f = Fixture(saveRates = true)
        f.viewModel.load(5)
        advanceUntilIdle()
        f.viewModel.setRate("USD", "")

        f.viewModel.submit()
        advanceUntilIdle()

        assertNull(f.state.error)
        assertEquals(LocalDate.of(2026, 6, 1), f.state.savedOn)
        assertEquals(0, BigDecimal("1.3").compareTo(f.storedExpense().rateToBase))
        assertTrue(f.rateRepo.upserts.isEmpty())
    }

    @Test
    fun submittingTwiceBeforeTheSaveCompletesReplacesTheEntryOnce() = runTest {
        val f = Fixture()
        f.viewModel.load(5)
        advanceUntilIdle()
        f.viewModel.setAmount("55")

        f.viewModel.submit()
        f.viewModel.submit()
        advanceUntilIdle()

        assertEquals(1, f.use.entries.replaces)
    }

    @Test
    fun submittingAgainAfterTheSaveCompletedBeforeAcknowledgingReplacesNothingMore() = runTest {
        val f = Fixture()
        f.viewModel.load(5)
        advanceUntilIdle()
        f.viewModel.setAmount("55")
        f.viewModel.submit()
        advanceUntilIdle()

        f.viewModel.submit()
        advanceUntilIdle()

        assertEquals(1, f.use.entries.replaces)
    }

    @Test
    fun saveIsNotOfferedWhileASavedDateIsWaitingToBeAcknowledged() = runTest {
        val f = Fixture()
        f.viewModel.load(5)
        advanceUntilIdle()
        assertTrue(f.state.canSubmit)
        f.viewModel.submit()
        advanceUntilIdle()

        assertEquals(LocalDate.of(2026, 6, 1), f.state.savedOn)
        assertFalse(f.state.canSubmit)
    }

    @Test
    fun savedOnIsTheDateThatWasSubmittedEvenIfTheFormDateChangesBeforeTheSaveCompletes() = runTest {
        val f = Fixture()
        f.viewModel.load(5)
        advanceUntilIdle()
        f.viewModel.setDate(LocalDate.of(2026, 7, 14))

        f.viewModel.submit()
        f.viewModel.setDate(LocalDate.of(2026, 9, 1))
        advanceUntilIdle()

        assertEquals(LocalDate.of(2026, 7, 14), f.storedExpense().date)
        assertEquals(LocalDate.of(2026, 7, 14), f.state.savedOn)
    }

    @Test
    fun anUnexpectedFailureInTheSaveShowsAMessageAndFreesTheForm() = runTest {
        val f = Fixture()
        f.viewModel.load(5)
        advanceUntilIdle()
        f.viewModel.setAmount("55")
        f.use.entries.failWith = IllegalStateException("the database is gone")

        f.viewModel.submit()
        advanceUntilIdle()

        assertEquals(UiMessage.Unexpected, f.state.error)
        assertFalse(f.state.isSubmitting)
        assertNull(f.state.savedOn)
    }

    @Test
    fun anUnexpectedFailureInTheDeleteShowsAMessageAndFreesTheForm() = runTest {
        val f = Fixture()
        f.viewModel.load(5)
        advanceUntilIdle()
        f.use.entries.failWith = IllegalStateException("the database is gone")

        f.viewModel.delete()
        advanceUntilIdle()

        assertEquals(UiMessage.Unexpected, f.state.error)
        assertFalse(f.state.isSubmitting)
        assertNull(f.state.undoableDelete)
        assertFalse(f.state.deleted)
    }

    @Test
    fun switchingToAnotherCurrencyAndBackShowsTheKeptRateAgainAndTheSaveKeepsIt() = runTest {
        val f = Fixture(saveRates = true)
        f.viewModel.load(5)
        advanceUntilIdle()

        f.viewModel.selectAccount(2) // SYP
        f.viewModel.selectAccount(1) // back to USD, where the entry was recorded

        assertEquals(listOf(RateField("USD", "1.3", isEditable = true)), f.state.rateFields)
        f.viewModel.submit()
        advanceUntilIdle()
        assertEquals(0, BigDecimal("1.3").compareTo(f.storedExpense().rateToBase))
        assertTrue(f.use.ledger.filledRates.isEmpty())
    }

    @Test
    fun aRateTypedForTheOtherCurrencyIsDroppedWhenSwitchingBackToTheKeptOne() = runTest {
        val f = Fixture(saveRates = true)
        f.viewModel.load(5)
        advanceUntilIdle()
        f.viewModel.selectAccount(2)
        f.viewModel.setRate("SYP", "0.5")
        f.viewModel.selectAccount(1)

        f.viewModel.submit()
        advanceUntilIdle()

        assertEquals(0, BigDecimal("1.3").compareTo(f.storedExpense().rateToBase))
        assertEquals(0, BigDecimal("0.0001").compareTo(f.rateRepo.findByCode("SYP")?.rateToBase))
        assertTrue(f.rateRepo.upserts.isEmpty())
    }

    @Test
    fun editingATransferShowsNoRateFieldsAndPassesNoTypedRates() = runTest {
        val f = Fixture(saveRates = true)
        f.use.entries.insert(transfer)
        f.viewModel.load(7)
        advanceUntilIdle()
        assertTrue(f.state.rateFields.isEmpty())

        f.viewModel.setRate("EUR", "2")
        f.viewModel.setRate("USD", "3")
        f.viewModel.setAmount("200")
        f.viewModel.submit()
        advanceUntilIdle()

        assertNull(f.state.error)
        assertTrue(f.state.rateFields.isEmpty())
        assertTrue(f.rateRepo.upserts.isEmpty())
        assertTrue(f.use.ledger.filledRates.isEmpty())
        val stored = f.use.entries.findById(7) as Transaction.Transfer
        assertEquals(0, BigDecimal("0.9").compareTo(stored.rateUsed))
        assertEquals(Money.of("180.00", "EUR"), stored.destAmount)
    }
}
