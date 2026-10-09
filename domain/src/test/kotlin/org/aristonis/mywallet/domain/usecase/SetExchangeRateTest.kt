package org.aristonis.mywallet.domain.usecase

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.aristonis.mywallet.domain.error.WalletException
import org.aristonis.mywallet.domain.model.Currency
import org.aristonis.mywallet.domain.model.Settings
import org.aristonis.mywallet.domain.usecase.fake.FakeSettingsRepository
import org.aristonis.mywallet.domain.model.Transaction
import org.aristonis.mywallet.domain.usecase.fake.EntryWorld
import org.aristonis.mywallet.domain.usecase.fake.DirectTransactionRunner
import org.aristonis.mywallet.domain.usecase.fake.FakeCurrencyRepository
import org.aristonis.mywallet.domain.usecase.fake.FakeEntryRepository
import org.aristonis.mywallet.domain.usecase.fake.FakeLedgerQueries
import org.aristonis.mywallet.domain.usecase.fake.FakeRateRepository
import org.aristonis.mywallet.domain.usecase.fake.RecordingLedgerQueries
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import java.math.BigDecimal

/**
 * Assumed signature: `SetExchangeRate(currencies, rates, ledger: LedgerQueries, runner: TransactionRunner,
 * settings: SettingsRepository)`. The runner is not nullable and the settings give the base currency
 * code. Saving a rate
 * stores it and gives it to every category leg in that currency that has no rate yet, on every
 * save, including a save of the value already stored. Legs that already carry a rate keep it.
 */
class SetExchangeRateTest {

    private val usd = Currency("USD", "$", 2)
    private val eur = Currency("EUR", "€", 2)

    private fun useCase(
        currencies: List<Currency> = listOf(usd, eur),
        rates: FakeRateRepository = FakeRateRepository(),
        entries: FakeEntryRepository = FakeEntryRepository(),
    ) = SetExchangeRate(FakeCurrencyRepository(currencies), rates, FakeLedgerQueries(entries), DirectTransactionRunner(), FakeSettingsRepository(Settings(baseCurrencyCode = "USD"))) to rates

    private suspend fun rateOf(entries: FakeEntryRepository, id: Long): BigDecimal? =
        (entries.findById(id) as Transaction.Expense).rateToBase

    private fun eurExpense(id: Long, rate: String? = null) =
        EntryWorld.expense(id = id, accountId = 4, amount = "10", currency = "EUR", rate = rate)

    private fun assertRate(expected: String, actual: BigDecimal?) {
        assertNotNull("a rate was expected but none is stored", actual)
        assertEquals("expected $expected but was $actual", 0, BigDecimal(expected).compareTo(actual))
    }

    @Test
    fun setsRateForATrackedCurrency() = runTest {
        val (setRate, rates) = useCase()

        setRate("EUR", BigDecimal("1.10"))

        val stored = rates.observeAll().first().single()
        assertEquals("EUR", stored.currencyCode)
        assertEquals(0, stored.rateToBase.compareTo(BigDecimal("1.10")))
    }

    @Test
    fun updatesAnExistingRateInPlace() = runTest {
        val (setRate, rates) = useCase()

        setRate("EUR", BigDecimal("1.10"))
        setRate("EUR", BigDecimal("1.25"))

        val stored = rates.observeAll().first()
        assertEquals(1, stored.size) // upsert replaced, did not append
        assertEquals(0, stored.single().rateToBase.compareTo(BigDecimal("1.25")))
    }

    @Test(expected = WalletException.CurrencyNotFound::class)
    fun unknownCurrencyFailsLoud() = runTest {
        val (setRate, _) = useCase()
        setRate("XXX", BigDecimal("1.10"))
    }

    @Test(expected = IllegalArgumentException::class)
    fun nonPositiveRateFailsLoud() = runTest {
        val (setRate, _) = useCase()
        setRate("EUR", BigDecimal.ZERO)
    }

    @Test
    fun theFirstRateSavedForACurrencyFillsItsLegsWithNoRateAndNoOthers() = runTest {
        val entries = FakeEntryRepository(
            listOf(
                eurExpense(id = 1, rate = null),
                eurExpense(id = 2, rate = null),
                eurExpense(id = 3, rate = "1.05"),
                EntryWorld.expense(id = 4, accountId = 2, currency = "SYP", rate = null),
            ),
        )
        val (setRate, _) = useCase(rates = FakeRateRepository(), entries = entries)

        setRate("EUR", BigDecimal("1.10"))

        assertRate("1.10", rateOf(entries, 1))
        assertRate("1.10", rateOf(entries, 2))
        assertRate("1.05", rateOf(entries, 3))
        assertNull("another currency waits for its own rate", rateOf(entries, 4))
    }

    @Test
    fun savingTheSameValueAgainStillFillsLegsThatHaveNoRate() = runTest {
        // An older write path can leave a NULL behind after the rate exists; re-saving must repair it.
        val entries = FakeEntryRepository(listOf(eurExpense(id = 1, rate = null)))
        val (setRate, _) = useCase(rates = FakeRateRepository(), entries = entries)
        setRate("EUR", BigDecimal("1.10"))
        entries.updateLegs { leg -> leg.copy(rateToBase = null) }

        setRate("EUR", BigDecimal("1.10"))

        assertRate("1.10", rateOf(entries, 1))
    }

    @Test
    fun changingASavedRateLeavesLegsRecordedAtTheOldRateAlone() = runTest {
        val entries = FakeEntryRepository(listOf(eurExpense(id = 1, rate = "1.10"), eurExpense(id = 2, rate = null)))
        val (setRate, _) = useCase(entries = entries)

        setRate("EUR", BigDecimal("1.25"))

        assertRate("1.10", rateOf(entries, 1))
        assertRate("1.25", rateOf(entries, 2))
    }

    @Test
    fun theFillGivesLegsTheRateExactlyAsTyped() = runTest {
        val entries = FakeEntryRepository(listOf(eurExpense(id = 1)))
        val (setRate, _) = useCase(entries = entries)

        setRate("EUR", BigDecimal("1.123456789012345678"))

        assertRate("1.123456789012345678", rateOf(entries, 1))
    }

    @Test
    fun anUnknownCurrencyFillsNothing() = runTest {
        val entries = FakeEntryRepository(listOf(EntryWorld.expense(id = 1, currency = "XXX", rate = null)))
        val (setRate, _) = useCase(entries = entries)

        runCatching { setRate("XXX", BigDecimal("2")) }

        assertNull(rateOf(entries, 1))
    }

    @Test
    fun aRateOfZeroFillsNothing() = runTest {
        val entries = FakeEntryRepository(listOf(eurExpense(id = 1)))
        val (setRate, _) = useCase(entries = entries)

        runCatching { setRate("EUR", BigDecimal.ZERO) }

        assertNull(rateOf(entries, 1))
    }

    private class Wired {
        val log = mutableListOf<String>()
        val rates = FakeRateRepository()
        val entries = FakeEntryRepository(listOf(EntryWorld.expense(id = 1, currency = "USD", rate = null)))
        val ledger = RecordingLedgerQueries(FakeLedgerQueries(entries))
        val setRate = SetExchangeRate(
            FakeCurrencyRepository(listOf(Currency("USD", "$", 2), Currency("EUR", "€", 2))),
            rates,
            ledger,
            DirectTransactionRunner(),
            FakeSettingsRepository(Settings(baseCurrencyCode = "USD")),
        )
    }

    @Test
    fun theBaseCurrencyCodeIsRefusedAndNothingIsWrittenOrFilled() = runTest {
        // A stored row for the base would be read as a real rate after the next base change.
        val wired = Wired()

        val refused = runCatching { wired.setRate("USD", BigDecimal("2")) }.exceptionOrNull()

        assertEquals(IllegalArgumentException::class, refused?.let { it::class })
        assertEquals(emptyList<Any>(), wired.rates.observeAll().first())
        assertEquals(emptyList<Any>(), wired.ledger.fills)
        assertNull(rateOf(wired.entries, 1))
    }

    @Test
    fun aCurrencyOtherThanTheBaseStillStoresAndFills() = runTest {
        val wired = Wired()

        wired.setRate("EUR", BigDecimal("1.10"))

        assertEquals(listOf("EUR"), wired.rates.observeAll().first().map { it.currencyCode })
        assertEquals(listOf("EUR"), wired.ledger.fills.map { it.first })
    }
}
