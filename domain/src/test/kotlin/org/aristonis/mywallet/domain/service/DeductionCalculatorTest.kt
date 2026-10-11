package org.aristonis.mywallet.domain.service

import org.aristonis.mywallet.domain.error.WalletException
import org.aristonis.mywallet.domain.model.DeductionInput
import org.aristonis.mywallet.domain.model.DeductionMode
import org.aristonis.mywallet.domain.model.DeductionTiming
import org.aristonis.mywallet.domain.model.DeductionTiming.LATER
import org.aristonis.mywallet.domain.model.DeductionTiming.NOW
import org.aristonis.mywallet.domain.model.Money
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.math.BigDecimal

/**
 * Money rules under test: each exact share is gross x percent / 100; the limit is checked on the exact
 * shares; lines round HALF_UP to the income currency; when the rounded lines add up to more than the
 * gross, the excess comes off the line with the largest rounding (ties: the last such line).
 */
class DeductionCalculatorTest {

    @Test
    fun tenPercentOf333Point33RoundsTo33Point33AndTheAccountReceivesTheRest() {
        val result = calculate(usd("333.33"), listOf(percent("10", NOW)))

        assertEquals(usd("33.33"), result.lines.single().amount)
        assertEquals(usd("33.33"), result.paidNow)
        assertEquals(usd("300.00"), result.accountReceives)
    }

    @Test
    fun roundsPercentDeductionsToUsdPrecisionWhenGrossWasTypedWithFewerDecimals() {
        val result = calculate(usd("100.0"), listOf(percent("12.5", NOW)))

        assertEquals(BigDecimal("12.50"), result.lines.single().amount.amount)
        assertEquals(BigDecimal("87.50"), result.accountReceives.amount)
    }

    @Test
    fun oneHundredPercentPaidNowLeavesAValidZeroAccountReceipt() {
        val result = calculate(usd("100.00"), listOf(percent("100", NOW)))

        assertEquals(usd("100.00"), result.lines.single().amount)
        assertEquals(usd("100.00"), result.paidNow)
        assertEquals(Money.zero("USD"), result.accountReceives)
    }

    @Test
    fun convertsAForeignFixedDeductionAtTheEntryRateThenRoundsToTheIncomeDecimals() {
        val result = calculate(usd("100.00"), listOf(fixed("10.00", "EUR", NOW)))

        assertEquals(usd("12.35"), result.lines.single().amount)
        assertEquals(usd("87.65"), result.accountReceives)
    }

    @Test
    fun paidNowCountsOnlyTheNowLinesAndLaterLinesStayOutOfTheReceipt() {
        val result = calculate(
            usd("100.00"),
            listOf(percent("12.5", NOW), fixed("20.00", "USD", NOW), percent("10", LATER)),
        )

        assertEquals(listOf(usd("12.50"), usd("20.00"), usd("10.00")), result.lines.map { it.amount })
        assertEquals(usd("32.50"), result.paidNow)
        assertEquals(usd("67.50"), result.accountReceives)
    }

    @Test
    fun aHalfCentRemainderInTheGrossStaysInTheAccountReceipt() {
        val result = calculate(usd("100.005"), listOf(fixed("0.01", "USD", NOW)))

        assertEquals(usd("0.01"), result.paidNow)
        assertEquals(usd("99.995"), result.accountReceives)
    }

    @Test
    fun refusesDeductionsWhoseTotalExceedsGrossIncome() {
        assertThrows(WalletException.DeductionsExceedIncome::class.java) {
            calculate(usd("100.00"), listOf(fixed("80.00", "USD", NOW), fixed("20.01", "USD", LATER)))
        }
    }

    @Test
    fun refusesSixtyPercentPlusFiftyPercent() {
        val error = assertThrows(WalletException.DeductionsExceedIncome::class.java) {
            calculate(usd("100.00"), listOf(percent("60", NOW), percent("50", LATER)))
        }

        assertEquals(usd("100.00"), error.gross)
        assertEquals(usd("110.00"), error.total)
    }

    // D62: the limit is checked on the exact shares.

    @Test
    fun fiftyFiftySplitOf1234Point55GivesTheExtraCentBackFromTheLastLine() {
        val result = calculate(usd("1234.55"), listOf(percent("50", NOW), percent("50", LATER)))

        assertEquals(listOf(usd("617.28"), usd("617.27")), result.lines.map { it.amount })
        assertEquals(usd("617.28"), result.paidNow)
        assertEquals(usd("617.27"), result.accountReceives)
    }

    @Test
    fun seventyThirtySplitOfFiveCentsIsAcceptedAndSumsToFiveCents() {
        val result = calculate(usd("0.05"), listOf(percent("70", NOW), percent("30", LATER)))

        assertEquals(listOf(usd("0.04"), usd("0.01")), result.lines.map { it.amount })
        assertEquals(usd("0.05"), result.lines.fold(Money.zero("USD")) { sum, line -> sum + line.amount })
        assertEquals(usd("0.04"), result.paidNow)
        assertEquals(usd("0.01"), result.accountReceives)
    }

    @Test
    fun oneHundredPercentPaidNowOn100Point005IsBroughtBackToTheExactGrossAndTheAccountReceivesZero() {
        val result = calculate(usd("100.005"), listOf(percent("100", NOW)))

        assertEquals(BigDecimal("100.005"), result.lines.single().amount.amount.stripTrailingZeros())
        assertEquals(usd("100.005"), result.paidNow)
        assertEquals(Money.zero("USD"), result.accountReceives)
    }

    @Test
    fun theExcessComesOffTheLineWithTheLargestRoundingNotAlwaysTheLastOne() {
        // exact 0.1251 / 0.375 / 0.4999 round up by 0.0049 / 0.005 / 0.0001: the middle line gives back the cent
        val result = calculate(
            usd("1.00"),
            listOf(percent("12.51", NOW), percent("37.5", LATER), percent("49.99", NOW)),
        )

        assertEquals(listOf(usd("0.13"), usd("0.37"), usd("0.50")), result.lines.map { it.amount })
        assertEquals(usd("0.63"), result.paidNow)
        assertEquals(usd("0.37"), result.accountReceives)
    }

    @Test
    fun linesThatRoundWithoutExceedingTheGrossAreLeftAsRounded() {
        val result = calculate(usd("1234.55"), listOf(percent("50", NOW), percent("40", LATER)))

        assertEquals(listOf(usd("617.28"), usd("493.82")), result.lines.map { it.amount })
        assertEquals(usd("617.27"), result.accountReceives)
    }

    // D45-1: an edited converted amount replaces the conversion and is still limit-checked.

    @Test
    fun anAmountOverrideIsUsedInsteadOfTheConversion() {
        val result = calculate(
            usd("100.00"),
            listOf(fixed("10.00", "EUR", NOW, override = usd("12.00"))),
        )

        assertEquals(usd("12.00"), result.lines.single().amount)
        assertEquals(usd("88.00"), result.accountReceives)
    }

    @Test
    fun anAmountOverrideNeedsNoRateForTheFixedCurrency() {
        val result = calculate(
            usd("100.00"),
            listOf(fixed("5.00", "GBP", LATER, override = usd("7.00"))),
        )

        assertEquals(usd("7.00"), result.lines.single().amount)
        assertEquals(usd("100.00"), result.accountReceives)
    }

    @Test
    fun anAmountOverrideAboveTheIncomeIsRefused() {
        assertThrows(WalletException.DeductionsExceedIncome::class.java) {
            calculate(usd("100.00"), listOf(fixed("10.00", "EUR", NOW, override = usd("100.01"))))
        }
    }

    @Test
    fun anAmountOverrideCountsTowardTheLimitWithTheOtherLines() {
        val error = assertThrows(WalletException.DeductionsExceedIncome::class.java) {
            calculate(
                usd("100.00"),
                listOf(percent("50", LATER), fixed("10.00", "EUR", NOW, override = usd("50.01"))),
            )
        }

        assertEquals(usd("100.01"), error.total)
    }

    // The value rules live on the model, so a bad input never reaches the calculator.

    @Test
    fun aFixedValueOfZeroHasItsOwnTypedError() {
        assertThrows(WalletException.DeductionValueNotPositive::class.java) { fixed("0", "USD", NOW) }
    }

    @Test
    fun aPercentAboveOneHundredIsRefusedWhereTheInputIsBuilt() {
        assertThrows(WalletException.DeductionOutOfRange::class.java) { percent("120", NOW) }
    }

    @Test
    fun aZeroPercentIsRefusedWithThePercentageError() {
        assertThrows(WalletException.DeductionOutOfRange::class.java) { percent("0", NOW) }
    }

    @Test
    fun aNegativeGrossIsRefused() {
        assertThrows(IllegalArgumentException::class.java) { calculate(usd("-1.00"), listOf(percent("10", NOW))) }
    }

    @Test
    fun noDeductionsLeavesTheWholeGrossInTheAccount() {
        val result = calculate(usd("100.00"), emptyList())

        assertEquals(emptyList<Money>(), result.lines.map { it.amount })
        assertEquals(Money.zero("USD"), result.paidNow)
        assertEquals(usd("100.00"), result.accountReceives)
    }

    // Coverage: currencies and conversion paths.

    @Test
    fun aFixedDeductionInTheIncomeCurrencyNeedsNoRate() {
        val bare = CurrencyConverter("USD", emptyMap(), mapOf("USD" to 2))

        val result = DeductionCalculator.calculate(usd("100.00"), listOf(fixed("25", "USD", NOW)), bare)

        assertEquals(usd("25.00"), result.lines.single().amount)
        assertEquals(2, result.lines.single().amount.amount.scale())
        assertEquals(usd("75.00"), result.accountReceives)
    }

    @Test
    fun aForeignFixedDeductionOnANonBaseIncomeConvertsThroughTheBase() {
        // base USD, EUR = 1.2345 USD: 10 USD -> 8.1004 EUR -> 8.10; 5 GBP at 1.5 -> 6.0753 EUR -> 6.08
        val result = calculate(
            eur("100.00"),
            listOf(fixed("10", "USD", NOW), fixed("5", "GBP", LATER)),
        )

        assertEquals(listOf(eur("8.10"), eur("6.08")), result.lines.map { it.amount })
        assertEquals(eur("8.10"), result.paidNow)
        assertEquals(eur("91.90"), result.accountReceives)
    }

    @Test
    fun aMissingRateForTheFixedCurrencyFailsAsMissingRate() {
        val error = assertThrows(WalletException.MissingRate::class.java) {
            calculate(usd("100.00"), listOf(fixed("5", "CHF", NOW)))
        }

        assertEquals("CHF", error.code)
    }

    @Test
    fun sypHasNoDecimalsSoALineRoundsToAWholeUnitAndGivesTheExtraUnitBack() {
        val result = calculate(syp("1001"), listOf(percent("50", NOW), percent("50", LATER)))

        assertEquals(listOf(syp("501"), syp("500")), result.lines.map { it.amount })
        assertEquals(0, result.lines.first().amount.amount.scale())
        assertEquals(syp("501"), result.paidNow)
        assertEquals(syp("500"), result.accountReceives)
    }

    @Test
    fun sypRoundsAHalfUnitUp() {
        val result = calculate(syp("12345"), listOf(percent("10", NOW)))

        assertEquals(syp("1235"), result.lines.single().amount)
        assertEquals(syp("11110"), result.accountReceives)
    }

    @Test
    fun kwdKeepsThreeDecimalsAndRoundsHalfUpAtTheThirdOne() {
        val result = calculate(kwd("10.001"), listOf(percent("12.5", NOW)))

        assertEquals(kwd("1.250"), result.lines.single().amount)
        assertEquals(3, result.lines.single().amount.amount.scale())
        assertEquals(kwd("8.751"), result.accountReceives)
    }

    @Test
    fun kwdHalfAThousandthRoundsUpToAThousandth() {
        val result = calculate(kwd("0.001"), listOf(percent("50", NOW)))

        assertEquals(kwd("0.001"), result.lines.single().amount)
        assertEquals(kwd("0.000"), result.accountReceives)
    }

    private fun calculate(gross: Money, deductions: List<DeductionInput>) =
        DeductionCalculator.calculate(gross, deductions, converter)

    private val converter = CurrencyConverter(
        baseCurrencyCode = "USD",
        ratesToBase = mapOf("EUR" to BigDecimal("1.2345"), "GBP" to BigDecimal("1.5")),
        decimalPlaces = mapOf("USD" to 2, "EUR" to 2, "GBP" to 2, "SYP" to 0, "KWD" to 3),
    )

    private fun percent(value: String, timing: DeductionTiming) = DeductionInput(
        mode = DeductionMode.PERCENT,
        value = BigDecimal(value),
        valueCurrencyCode = null,
        timing = timing,
    )

    private fun fixed(value: String, currencyCode: String, timing: DeductionTiming, override: Money? = null) =
        DeductionInput(
            mode = DeductionMode.FIXED,
            value = BigDecimal(value),
            valueCurrencyCode = currencyCode,
            timing = timing,
            amountOverride = override,
        )

    private fun usd(amount: String) = Money.of(amount, "USD")
    private fun eur(amount: String) = Money.of(amount, "EUR")
    private fun syp(amount: String) = Money.of(amount, "SYP")
    private fun kwd(amount: String) = Money.of(amount, "KWD")
}
