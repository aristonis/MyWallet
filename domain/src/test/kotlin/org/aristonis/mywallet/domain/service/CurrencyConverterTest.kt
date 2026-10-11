package org.aristonis.mywallet.domain.service

import org.aristonis.mywallet.domain.error.WalletException
import org.aristonis.mywallet.domain.model.Money
import org.junit.Assert.assertEquals
import org.junit.Test
import java.math.BigDecimal

class CurrencyConverterTest {

    // base = USD. Rates: value of 1 unit in USD. EUR = 1.10, JPY = 0.0067.
    private fun converter(
        base: String = "USD",
        rates: Map<String, String> = emptyMap(),
        decimals: Map<String, Int> = emptyMap(),
    ) = CurrencyConverter(base, rates.mapValues { BigDecimal(it.value) }, decimals)

    @Test
    fun sameCurrency_returnsUnchanged() {
        val c = converter()
        assertEquals(Money.of("10", "USD"), c.convert(Money.of("10", "USD"), "USD"))
    }

    @Test
    fun baseToOther() {
        // 11 USD -> EUR: 11 / 1.10 = 10.00
        val c = converter(rates = mapOf("EUR" to "1.10"), decimals = mapOf("EUR" to 2))
        assertEquals(Money.of("10.00", "EUR"), c.convert(Money.of("11", "USD"), "EUR"))
    }

    @Test
    fun otherToBase() {
        // 100 EUR -> USD: 100 * 1.10 = 110.00
        val c = converter(rates = mapOf("EUR" to "1.10"), decimals = mapOf("USD" to 2))
        assertEquals(Money.of("110.00", "USD"), c.convert(Money.of("100", "EUR"), "USD"))
    }

    @Test
    fun crossNonBase_roundsToTargetDecimals() {
        // 100 EUR -> JPY: base 110 USD; / 0.0067 = 16417.91... -> 0 dp -> 16418
        val c = converter(rates = mapOf("EUR" to "1.10", "JPY" to "0.0067"), decimals = mapOf("JPY" to 0))
        assertEquals(Money.of("16418", "JPY"), c.convert(Money.of("100", "EUR"), "JPY"))
    }

    @Test(expected = WalletException.MissingRate::class)
    fun missingRate_failsLoud() {
        converter().convert(Money.of("1", "EUR"), "USD") // no EUR rate configured
    }

    @Test
    fun decimalPlacesFor_returnsTheConfiguredPrecision() {
        val c = converter(decimals = mapOf("SYP" to 0, "KWD" to 3))

        assertEquals(0, c.decimalPlacesFor("SYP"))
        assertEquals(3, c.decimalPlacesFor("KWD"))
    }

    @Test
    fun decimalPlacesFor_fallsBackToTheDefaultWhenTheCurrencyIsNotConfigured() {
        assertEquals(CurrencyConverter.DEFAULT_DECIMALS, converter().decimalPlacesFor("XYZ"))
        assertEquals(2, converter().decimalPlacesFor("USD"))
    }
}
