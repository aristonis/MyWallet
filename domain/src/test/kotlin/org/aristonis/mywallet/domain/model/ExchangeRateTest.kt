package org.aristonis.mywallet.domain.model

import org.junit.Assert.assertEquals
import org.junit.Test
import java.math.BigDecimal

class ExchangeRateTest {

    @Test
    fun holdsRate() {
        val r = ExchangeRate("EUR", BigDecimal("1.08"))
        assertEquals(BigDecimal("1.08"), r.rateToBase)
    }

    @Test(expected = IllegalArgumentException::class)
    fun zeroRate_throws() {
        ExchangeRate("EUR", BigDecimal.ZERO)
    }

    @Test(expected = IllegalArgumentException::class)
    fun negativeRate_throws() {
        ExchangeRate("EUR", BigDecimal("-1"))
    }

    @Test(expected = IllegalArgumentException::class)
    fun blankCurrency_throws() {
        ExchangeRate("", BigDecimal.ONE)
    }
}
