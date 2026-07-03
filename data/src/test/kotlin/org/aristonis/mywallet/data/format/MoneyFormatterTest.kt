package org.aristonis.mywallet.data.format

import org.aristonis.mywallet.domain.model.Currency
import org.aristonis.mywallet.domain.model.Money
import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.Locale

/**
 * Locale-aware grouping/decimal separators, exactly [decimalPlaces] fraction digits, HALF_UP
 * rounding, and an ISO code suffix. Locales are passed explicitly so the results are deterministic
 * regardless of the host machine's default locale.
 */
class MoneyFormatterTest {

    private val usFormatter = MoneyFormatter(Locale.US)
    private val germanFormatter = MoneyFormatter(Locale.GERMANY)

    @Test
    fun us_twoDecimals_padsAndGroups() {
        assertEquals("1,000.50 USD", usFormatter.format("1000.5".toBigDecimal(), 2, "USD"))
    }

    @Test
    fun us_zeroDecimals_noFractionPart() {
        assertEquals("1,000 JPY", usFormatter.format("1000".toBigDecimal(), 0, "JPY"))
    }

    @Test
    fun us_threeDecimals_padsToThree() {
        assertEquals("12.500 KWD", usFormatter.format("12.5".toBigDecimal(), 3, "KWD"))
    }

    @Test
    fun germany_usesDotGroupingAndCommaDecimal() {
        assertEquals("1.000,50 EUR", germanFormatter.format("1000.5".toBigDecimal(), 2, "EUR"))
    }

    @Test
    fun us_roundsHalfUp() {
        assertEquals("1.01 USD", usFormatter.format("1.005".toBigDecimal(), 2, "USD"))
    }

    @Test
    fun us_negativeAmount_keepsSign() {
        assertEquals("-40.00 USD", usFormatter.format("-40".toBigDecimal(), 2, "USD"))
    }

    @Test
    fun convenienceOverload_delegatesToPrimitive() {
        val money = Money.of("12.5", "KWD")
        val currency = Currency(code = "KWD", symbol = "KD", decimalPlaces = 3)
        assertEquals("12.500 KWD", usFormatter.format(money, currency))
    }
}
