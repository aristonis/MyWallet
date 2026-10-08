package org.aristonis.mywallet.domain.usecase

import kotlinx.coroutines.test.runTest
import org.aristonis.mywallet.domain.error.WalletException
import org.aristonis.mywallet.domain.model.Currency
import org.aristonis.mywallet.domain.model.ExchangeRate
import org.aristonis.mywallet.domain.usecase.fake.DirectTransactionRunner
import org.aristonis.mywallet.domain.usecase.fake.FakeCurrencyRepository
import org.aristonis.mywallet.domain.usecase.fake.FakeRateRepository
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.math.BigDecimal

/**
 * A rate typed on a form is kept only if the transaction it was typed for is recorded, and both
 * happen in one database transaction. A transfer refused after the rate was saved would otherwise
 * leave a wrong rate repricing every total, behind an error that says nothing changed.
 */
class RecordWithTypedRatesTest {

    private val currencies = FakeCurrencyRepository(listOf(Currency("SYP", "£S", 2), Currency("USD", "$", 2)))

    @Test
    fun keepsTheRatesOnceTheTransactionIsRecorded() = runTest {
        val rates = FakeRateRepository(listOf(ExchangeRate("USD", BigDecimal("4"))))
        val runner = DirectTransactionRunner()

        val id = RecordWithTypedRates(runner, SetExchangeRate(currencies, rates))(mapOf("USD" to BigDecimal("5"))) { 42L }

        assertEquals(42L, id)
        assertEquals(0, BigDecimal("5").compareTo(rates.findByCode("USD")!!.rateToBase))
        assertEquals(1, runner.runs)
    }

    @Test
    fun aRefusedTransactionKeepsNoRate() = runTest {
        val rates = FakeRateRepository(listOf(ExchangeRate("USD", BigDecimal("4"))))

        assertThrows(WalletException.AmountRoundsToZero::class.java) {
            kotlinx.coroutines.runBlocking {
                RecordWithTypedRates(DirectTransactionRunner(), SetExchangeRate(currencies, rates))(
                    mapOf("USD" to BigDecimal("0.0000769")),
                ) { throw WalletException.AmountRoundsToZero("SYP") }
            }
        }

        assertEquals(0, BigDecimal("4").compareTo(rates.findByCode("USD")!!.rateToBase))
    }
}
