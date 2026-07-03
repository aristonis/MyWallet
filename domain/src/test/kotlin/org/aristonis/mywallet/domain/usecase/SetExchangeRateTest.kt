package org.aristonis.mywallet.domain.usecase

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.aristonis.mywallet.domain.error.WalletException
import org.aristonis.mywallet.domain.model.Currency
import org.aristonis.mywallet.domain.usecase.fake.FakeCurrencyRepository
import org.aristonis.mywallet.domain.usecase.fake.FakeRateRepository
import org.junit.Assert.assertEquals
import org.junit.Test
import java.math.BigDecimal

class SetExchangeRateTest {

    private val usd = Currency("USD", "$", 2)
    private val eur = Currency("EUR", "€", 2)

    private fun useCase(
        currencies: List<Currency> = listOf(usd, eur),
        rates: FakeRateRepository = FakeRateRepository(),
    ) = SetExchangeRate(FakeCurrencyRepository(currencies), rates) to rates

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
}
