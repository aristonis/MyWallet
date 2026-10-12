package org.aristonis.mywallet.domain.service

import org.aristonis.mywallet.domain.model.FeeMode
import org.aristonis.mywallet.domain.model.FeeRule
import org.aristonis.mywallet.domain.model.Money
import org.junit.Assert.assertEquals
import org.junit.Test
import java.math.BigDecimal

class FeeCalculatorTest {

    private val converter = CurrencyConverter(
        baseCurrencyCode = "USD",
        ratesToBase = emptyMap(),
        decimalPlaces = mapOf("USD" to 2, "JPY" to 0),
    )

    private fun money(amount: String, currency: String = "USD") = Money.of(amount, currency)

    @Test
    fun calculatesTwoPercentOfThreeHundredAsSix() {
        val fee = FeeCalculator.calculate(
            sent = money("300"),
            rule = FeeRule(FeeMode.PERCENT, BigDecimal("2")),
            converter = converter,
        )

        assertEquals(money("6.00"), fee)
    }

    @Test
    fun calculatesFixedFeeInTheSendingCurrency() {
        val fee = FeeCalculator.calculate(
            sent = money("300"),
            rule = FeeRule(FeeMode.FIXED, BigDecimal("1.25")),
            converter = converter,
        )

        assertEquals(money("1.25"), fee)
    }

    @Test
    fun zeroPercentAndZeroFixedRulesProduceNoFee() {
        val percentFee = FeeCalculator.calculate(
            sent = money("300"),
            rule = FeeRule(FeeMode.PERCENT, BigDecimal.ZERO),
            converter = converter,
        )
        val fixedFee = FeeCalculator.calculate(
            sent = money("300"),
            rule = FeeRule(FeeMode.FIXED, BigDecimal.ZERO),
            converter = converter,
        )

        assertEquals(money("0"), percentFee)
        assertEquals(money("0"), fixedFee)
    }

    @Test
    fun roundsPercentFeeToSendingCurrencyPrecision() {
        val fee = FeeCalculator.calculate(
            sent = money("1.03"),
            rule = FeeRule(FeeMode.PERCENT, BigDecimal("2.5")),
            converter = converter,
        )

        assertEquals(money("0.03"), fee)
    }

    @Test
    fun usesSendingCurrencyPrecisionForCurrenciesWithNoFractionDigits() {
        val fee = FeeCalculator.calculate(
            sent = money("101", "JPY"),
            rule = FeeRule(FeeMode.PERCENT, BigDecimal("2.5")),
            converter = converter,
        )

        assertEquals(money("3", "JPY"), fee)
    }

    @Test
    fun anExpenseFeeMayEqualOrExceedItsPrincipal() {
        for (value in listOf("10", "10.01")) {
            val fee = FeeCalculator.calculate(
                sent = money("10"), rule = FeeRule(FeeMode.FIXED, BigDecimal(value)), converter = converter,
            )
            assertEquals(money(value), fee)
        }
    }
}
