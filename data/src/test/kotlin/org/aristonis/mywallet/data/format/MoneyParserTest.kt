package org.aristonis.mywallet.data.format

import org.aristonis.mywallet.domain.model.Money
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.math.BigDecimal
import java.util.Locale

/**
 * Amount input is parsed locale-aware (the device's decimal/grouping separators) yet EXACT (BigDecimal,
 * never a float) and STRICT (the whole string must be a number, so trailing garbage fails). Locales are
 * passed explicitly so the results never depend on the host machine's default locale.
 */
class MoneyParserTest {

    private val us = MoneyParser(Locale.US)
    private val germany = MoneyParser(Locale.GERMANY)

    // --- locale-aware, exact ---

    @Test
    fun us_groupedInput_isRejected() {
        // Input takes a plain number, not a grouped one — the grouped form is a display concern.
        val ex = assertThrows(MoneyParseException::class.java) { us.parseAmount("1,000.50", "USD") }
        assertEquals(MoneyParseError.NOT_A_NUMBER, ex.error)
    }

    @Test
    fun us_plainDecimal_parsesExact() {
        assertEquals(Money.of("1.50", "USD"), us.parseAmount("1.50", "USD"))
    }

    @Test
    fun germany_commaIsTheDecimalSeparator() {
        assertEquals(Money.of("1.50", "EUR"), germany.parseAmount("1,50", "EUR"))
    }

    @Test
    fun germany_groupedInput_isRejected() {
        val ex = assertThrows(MoneyParseException::class.java) { germany.parseAmount("1.000,50", "EUR") }
        assertEquals(MoneyParseError.NOT_A_NUMBER, ex.error)
    }

    // --- grouping separators are rejected on input: a money field takes a plain number, never grouped,
    //     so a stray separator can't be misread as a 10x/100x value ---

    @Test
    fun us_commaDecimalTypo_isRejected() {
        // "1,50" in en-US must NOT parse to 150 (',' = grouping) — reject it rather than record 100x.
        val ex = assertThrows(MoneyParseException::class.java) { us.parseAmount("1,50", "USD") }
        assertEquals(MoneyParseError.NOT_A_NUMBER, ex.error)
    }

    @Test
    fun germany_dotDecimalTypo_isRejected() {
        // "1.50" in de-DE must NOT parse to 150 ('.' = grouping there).
        val ex = assertThrows(MoneyParseException::class.java) { germany.parseAmount("1.50", "EUR") }
        assertEquals(MoneyParseError.NOT_A_NUMBER, ex.error)
    }

    // --- prefill round-trips: parse(toInputString(x)) == x in every locale (edit screens rely on this) ---

    @Test
    fun toInputString_roundTripsThroughParse_us() {
        assertEquals(Money.of("1000.50", "USD"), us.parseOpeningBalance(us.toInputString(BigDecimal("1000.50")), "USD"))
    }

    @Test
    fun toInputString_roundTripsThroughParse_germany() {
        // de-DE prefill uses a comma; the parser reads it back to the same value, never 100050.
        assertEquals("1000,50", germany.toInputString(BigDecimal("1000.50")))
        assertEquals(Money.of("1000.50", "EUR"), germany.parseOpeningBalance(germany.toInputString(BigDecimal("1000.50")), "EUR"))
    }

    // --- strictness: the whole trimmed string must be consumed ---

    @Test
    fun trailingGarbage_isRejected() {
        val ex = assertThrows(MoneyParseException::class.java) { us.parseAmount("1,50abc", "USD") }
        assertEquals(MoneyParseError.NOT_A_NUMBER, ex.error)
    }

    @Test
    fun nonNumeric_isRejected() {
        val ex = assertThrows(MoneyParseException::class.java) { us.parseAmount("abc", "USD") }
        assertEquals(MoneyParseError.NOT_A_NUMBER, ex.error)
    }

    // --- blank, per flavor ---

    @Test
    fun blankAmount_isRejected() {
        val ex = assertThrows(MoneyParseException::class.java) { us.parseAmount("   ", "USD") }
        assertEquals(MoneyParseError.AMOUNT_MISSING, ex.error)
    }

    @Test
    fun blankRate_isRejected() {
        val ex = assertThrows(MoneyParseException::class.java) { us.parseRate("") }
        assertEquals(MoneyParseError.RATE_MISSING, ex.error)
    }

    @Test
    fun blankOpeningBalance_isZero() {
        assertEquals(Money.zero("USD"), us.parseOpeningBalance("   ", "USD"))
    }

    // --- sign rules ---

    @Test
    fun zeroAmount_isRejected() {
        val ex = assertThrows(MoneyParseException::class.java) { us.parseAmount("0", "USD") }
        assertEquals(MoneyParseError.AMOUNT_NOT_POSITIVE, ex.error)
    }

    @Test
    fun negativeAmount_isRejected() {
        val ex = assertThrows(MoneyParseException::class.java) { us.parseAmount("-5", "USD") }
        assertEquals(MoneyParseError.AMOUNT_NOT_POSITIVE, ex.error)
    }

    @Test
    fun zeroRate_isRejected() {
        val ex = assertThrows(MoneyParseException::class.java) { us.parseRate("0") }
        assertEquals(MoneyParseError.RATE_NOT_POSITIVE, ex.error)
    }

    @Test
    fun validRate_parsesExact() {
        assertEquals(0, BigDecimal("1.10").compareTo(us.parseRate("1.10")))
    }

    @Test
    fun openingBalance_allowsNegative() {
        assertEquals(Money.of("-40", "USD"), us.parseOpeningBalance("-40", "USD"))
    }

    // --- absurd exponent: rejected at the boundary, never reaches toPlainString (OOM) ---

    @Test
    fun amount_absurdExponent_isRejected() {
        val ex = assertThrows(MoneyParseException::class.java) { us.parseAmount("1E40", "USD") }
        assertEquals(MoneyParseError.AMOUNT_OUT_OF_RANGE, ex.error)
    }

    @Test
    fun rate_absurdExponent_isRejected() {
        val ex = assertThrows(MoneyParseException::class.java) { us.parseRate("1E2000000000") }
        assertEquals(MoneyParseError.RATE_OUT_OF_RANGE, ex.error)
    }

    @Test
    fun openingBalance_absurdExponent_isRejected() {
        val ex = assertThrows(MoneyParseException::class.java) { us.parseOpeningBalance("1E40", "USD") }
        assertEquals(MoneyParseError.AMOUNT_OUT_OF_RANGE, ex.error)
    }
}
