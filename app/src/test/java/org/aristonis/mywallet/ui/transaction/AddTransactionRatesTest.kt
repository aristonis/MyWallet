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
import org.aristonis.mywallet.domain.usecase.RecordExpense
import org.aristonis.mywallet.domain.usecase.RecordIncome
import org.aristonis.mywallet.domain.usecase.RecordTransfer
import org.aristonis.mywallet.domain.usecase.RecordWithTypedRates
import org.aristonis.mywallet.domain.usecase.SetExchangeRate
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
 * A payment or transfer in a currency other than the base shows its rate on the form, can fix it
 * there, and says what the amount is worth in the base currency, so nobody has to leave the form for
 * Settings and come back.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AddTransactionRatesTest {

    private val dispatcher = StandardTestDispatcher()

    @Before fun setUp() = Dispatchers.setMain(dispatcher)

    @After fun tearDown() = Dispatchers.resetMain()

    private val currencies = listOf(Currency("SYP", "£S", 2), Currency("USD", "$", 2), Currency("EUR", "€", 2))
    private val food = Category(id = 20, name = "Food", kind = CategoryKind.EXPENSE)

    private fun account(id: Long, currency: String) =
        Account(id = id, name = "acct-$id", typeKey = "cash", currencyCode = currency, openingBalance = Money.of("1000", currency))

    private inner class Fixture(
        rates: List<ExchangeRate> = listOf(ExchangeRate("USD", BigDecimal("4"))),
        saveRates: Boolean = true,
        accounts: List<Account> = listOf(account(1, "USD"), account(2, "SYP"), account(3, "EUR")),
        locale: Locale = Locale.US,
        base: String = "SYP",
    ) {
        val accountRepo = FakeAccountRepository(accounts)
        val categoryRepo = FakeCategoryRepository(listOf(food))
        val txRepo = FakeTransactionRepository()
        val currencyRepo = FakeCurrencyRepository(currencies)
        val rateRepo = FakeRateRepository(rates)
        val settingsRepo = FakeSettingsRepository(Settings(baseCurrencyCode = base, saveTransactionRates = saveRates))
        val viewModel = AddTransactionViewModel(
            accounts = accountRepo,
            categories = categoryRepo,
            recordIncome = RecordIncome(accountRepo, categoryRepo, txRepo),
            recordExpense = RecordExpense(accountRepo, categoryRepo, txRepo),
            recordTransfer = RecordTransfer(accountRepo, currencyRepo, rateRepo, settingsRepo, txRepo),
            today = TodayProvider { LocalDate.of(2026, 10, 8) },
            moneyParser = MoneyParser(locale),
            fx = FakeFxRepository(currencyRepo, rateRepo, settingsRepo),
            settings = settingsRepo,
            moneyFormatter = MoneyFormatter(locale),
            recordWithTypedRates = RecordWithTypedRates(DirectTransactionRunner(), SetExchangeRate(currencyRepo, rateRepo)),
        )
        val state get() = viewModel.state.value

        suspend fun savedRate(code: String) = rateRepo.findByCode(code)?.rateToBase
    }

    private fun Fixture.expense(accountId: Long, amount: String) {
        viewModel.selectType(TransactionType.EXPENSE)
        viewModel.selectAccount(accountId)
        viewModel.selectCategory(food.id)
        viewModel.setAmount(amount)
    }

    private fun Fixture.transfer(from: Long, to: Long, amount: String) {
        viewModel.selectType(TransactionType.TRANSFER)
        viewModel.selectAccount(from)
        viewModel.selectDestAccount(to)
        viewModel.setAmount(amount)
    }

    @Test
    fun aBaseCurrencyPaymentShowsNoRateAndNoHint() = runTest {
        val f = Fixture()
        advanceUntilIdle()

        f.expense(accountId = 2, amount = "8")
        advanceUntilIdle()

        assertTrue(f.state.rateFields.isEmpty())
        assertNull(f.state.baseEquivalent)
    }

    @Test
    fun aForeignPaymentShowsItsRateAndWhatItIsWorthInTheBase() = runTest {
        val f = Fixture()
        advanceUntilIdle()

        f.expense(accountId = 1, amount = "2")
        advanceUntilIdle()

        assertEquals(listOf(RateField("USD", "4", isEditable = true)), f.state.rateFields)
        assertEquals("8.00 SYP", f.state.baseEquivalent)
    }

    @Test
    fun theHintFollowsATypedRate() = runTest {
        val f = Fixture()
        advanceUntilIdle()
        f.expense(accountId = 1, amount = "2")

        f.viewModel.setRate("USD", "5")
        advanceUntilIdle()

        assertEquals("10.00 SYP", f.state.baseEquivalent)
    }

    @Test
    fun withSavingOnATypedRateBecomesTheSavedRate() = runTest {
        val f = Fixture(saveRates = true)
        advanceUntilIdle()
        f.expense(accountId = 1, amount = "2")
        f.viewModel.setRate("USD", "5")

        f.viewModel.submit()
        advanceUntilIdle()

        assertTrue(f.state.saved)
        assertEquals(0, BigDecimal("5").compareTo(f.savedRate("USD")))
        assertEquals(Money.of("2", "USD"), (f.txRepo.added.single() as Transaction.Expense).amount)
    }

    @Test
    fun withSavingOffAPaymentRateIsReadOnly() = runTest {
        val f = Fixture(saveRates = false)
        advanceUntilIdle()

        f.expense(accountId = 1, amount = "2")
        advanceUntilIdle()

        assertEquals(listOf(RateField("USD", "4", isEditable = false)), f.state.rateFields)
        f.viewModel.submit()
        advanceUntilIdle()
        assertEquals(0, BigDecimal("4").compareTo(f.savedRate("USD")))
    }

    @Test
    fun aTransferWithNoRateWaitsForOneOnTheForm() = runTest {
        val f = Fixture(rates = emptyList())
        advanceUntilIdle()

        f.transfer(from = 1, to = 2, amount = "2")
        advanceUntilIdle()
        assertEquals(listOf(RateField("USD", "", isEditable = true)), f.state.rateFields)
        assertTrue(f.state.rateRequired)
        assertFalse(f.state.canSubmit)

        f.viewModel.setRate("USD", "4")
        advanceUntilIdle()
        assertFalse(f.state.rateRequired)
        f.viewModel.submit()
        advanceUntilIdle()

        assertEquals(Money.of("8", "SYP"), (f.txRepo.added.single() as Transaction.Transfer).destAmount)
        assertEquals(0, BigDecimal("4").compareTo(f.savedRate("USD")))
    }

    @Test
    fun withSavingOffATypedTransferRateIsForThisTransferOnly() = runTest {
        val f = Fixture(saveRates = false)
        advanceUntilIdle()
        f.transfer(from = 1, to = 2, amount = "2")
        f.viewModel.setRate("USD", "5")

        f.viewModel.submit()
        advanceUntilIdle()

        assertEquals(Money.of("10", "SYP"), (f.txRepo.added.single() as Transaction.Transfer).destAmount)
        assertEquals(0, BigDecimal("4").compareTo(f.savedRate("USD")))
    }

    @Test
    fun aCrossTransferShowsWhatTheSourceBuysAndWhatArrives() = runTest {
        val f = Fixture(rates = listOf(ExchangeRate("USD", BigDecimal("4.4")), ExchangeRate("EUR", BigDecimal("4"))))
        advanceUntilIdle()

        f.transfer(from = 1, to = 3, amount = "100")
        advanceUntilIdle()

        assertEquals(listOf("USD", "EUR"), f.state.rateFields.map { it.currencyCode })
        assertEquals(PairRate(from = "USD", to = "EUR", rate = "1.1"), f.state.pairRate)
        assertEquals("110.00 EUR", f.state.receivedDisplay)
    }

    @Test
    fun aSameCurrencyTransferNeedsNoRate() = runTest {
        val f = Fixture(rates = emptyList(), accounts = listOf(account(1, "USD"), account(4, "USD")))
        advanceUntilIdle()

        f.transfer(from = 1, to = 4, amount = "25")
        advanceUntilIdle()

        assertTrue(f.state.rateFields.isEmpty())
        assertFalse(f.state.rateRequired)
        assertNull(f.state.pairRate)
        f.viewModel.submit()
        advanceUntilIdle()
        assertEquals(Money.of("25", "USD"), (f.txRepo.added.single() as Transaction.Transfer).destAmount)
        assertNull(f.savedRate("USD")) // no rate was invented and saved
    }

    @Test
    fun whatThePreviewSaysArrivesIsWhatIsStored() = runTest {
        val f = Fixture(rates = listOf(ExchangeRate("USD", BigDecimal("4")), ExchangeRate("EUR", BigDecimal("4"))))
        advanceUntilIdle()
        f.transfer(from = 1, to = 3, amount = "10")
        f.viewModel.setRate("USD", "3.333333")
        advanceUntilIdle()
        val preview = f.state.receivedDisplay

        f.viewModel.submit()
        advanceUntilIdle()

        val stored = (f.txRepo.added.single() as Transaction.Transfer).destAmount
        assertEquals(MoneyFormatter(Locale.US).format(stored, currencies.first { it.code == "EUR" }), preview)
    }

    @Test
    fun aCommaDecimalLocaleShowsAndReadsRatesItsOwnWay() = runTest {
        val f = Fixture(
            rates = listOf(ExchangeRate("USD", BigDecimal("4.4")), ExchangeRate("EUR", BigDecimal("4"))),
            locale = Locale.GERMANY,
        )
        advanceUntilIdle()

        f.transfer(from = 1, to = 3, amount = "100")
        advanceUntilIdle()

        assertEquals("4,4", f.state.rateFields.first { it.currencyCode == "USD" }.input)
        assertEquals(PairRate(from = "USD", to = "EUR", rate = "1,1"), f.state.pairRate)
    }

    @Test
    fun aRateThatDoesNotParseIsFlaggedAndHidesThePreview() = runTest {
        val f = Fixture(locale = Locale.GERMANY)
        advanceUntilIdle()
        f.expense(accountId = 1, amount = "2")

        f.viewModel.setRate("USD", "4.5") // "." is grouping in German, so this is not a rate
        advanceUntilIdle()

        assertTrue(f.state.rateFields.single().isError)
        assertNull(f.state.baseEquivalent)
    }

    @Test
    fun aSmallRateKeepsItsSignificantDigits() = runTest {
        // Base USD; 1 SYP = 1/13000 USD. Fixed decimal places would print 0.000077 (two digits, 0.1% off).
        val f = Fixture(
            rates = listOf(ExchangeRate("SYP", BigDecimal("0.0000769230769"))),
            accounts = listOf(account(2, "SYP"), account(1, "USD")),
            base = "USD",
        )
        advanceUntilIdle()

        f.transfer(from = 2, to = 1, amount = "13000")
        advanceUntilIdle()

        assertEquals(PairRate(from = "SYP", to = "USD", rate = "0.0000769231"), f.state.pairRate)
    }

    @Test
    fun aWrongWayRateOnARefusedTransferIsNeverSaved() = runTest {
        val f = Fixture(saveRates = true)
        advanceUntilIdle()
        f.transfer(from = 1, to = 2, amount = "0.01")
        f.viewModel.setRate("USD", "0.0000769") // typed the wrong way round: 0.01 $ becomes 0.00 SYP

        f.viewModel.submit()
        advanceUntilIdle()

        assertTrue(f.state.error != null)
        assertTrue(f.txRepo.added.isEmpty())
        assertEquals(0, BigDecimal("4").compareTo(f.savedRate("USD"))) // the saved rate is untouched
    }

    @Test
    fun clearingTheRateOfATransferBlocksSaving() = runTest {
        val f = Fixture()
        advanceUntilIdle()
        f.transfer(from = 1, to = 2, amount = "2")

        f.viewModel.setRate("USD", "")
        advanceUntilIdle()

        assertTrue(f.state.rateRequired)
        assertFalse(f.state.canSubmit)
    }

    @Test
    fun bothTypedRatesOfACrossTransferAreKept() = runTest {
        val f = Fixture(rates = listOf(ExchangeRate("USD", BigDecimal("4")), ExchangeRate("EUR", BigDecimal("4.4"))))
        advanceUntilIdle()
        f.transfer(from = 1, to = 3, amount = "10")
        f.viewModel.setRate("USD", "4.5")
        f.viewModel.setRate("EUR", "5")

        f.viewModel.submit()
        advanceUntilIdle()

        assertEquals(0, BigDecimal("4.5").compareTo(f.savedRate("USD")))
        assertEquals(0, BigDecimal("5").compareTo(f.savedRate("EUR")))
    }
}
