package org.aristonis.mywallet.domain.service

import org.aristonis.mywallet.domain.error.WalletException
import org.aristonis.mywallet.domain.model.DeductionInput
import org.aristonis.mywallet.domain.model.DeductionMode
import org.aristonis.mywallet.domain.model.DeductionResult
import org.aristonis.mywallet.domain.model.DeductionTiming
import org.aristonis.mywallet.domain.model.Money
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigDecimal
import java.util.Random
import java.util.TreeSet

/**
 * Generated inputs with a fixed seed, so a failure replays. The limit is checked on the exact shares:
 * a request is refused only when the exact total is above the gross, and every accepted request stores
 * lines that never exceed the gross and split it exactly into account receipt plus paid now.
 */
class DeductionCalculatorPropertyTest {

    private val converter = CurrencyConverter(
        baseCurrencyCode = "USD",
        ratesToBase = mapOf("EUR" to BigDecimal("1.2345")),
        decimalPlaces = mapOf("USD" to 2, "EUR" to 2),
    )
    private val random = Random(SEED)

    @Test
    fun percentSplitsUpToOneHundredAreNeverRefusedAndAlwaysConserveTheGross() {
        repeat(ITERATIONS) { round ->
            val gross = randomGross()
            val inputs = randomPercentSplit()

            val result = DeductionCalculator.calculate(gross, inputs, converter)

            assertInvariants("round $round gross $gross inputs ${describe(inputs)}", gross, result)
        }
    }

    @Test
    fun halfUnitSharesOnOddGrossesStillConserveTheGrossAndNeverExceedIt() {
        val splits = listOf(listOf("50", "50"), listOf("70", "30"), listOf("30", "70"), listOf("25", "75"), listOf("10", "90"))
        repeat(ITERATIONS) { round ->
            val gross = Money.of(BigDecimal.valueOf(random.nextInt(MAX_GROSS_UNITS / 2) * 2L + 1, 2), "USD")
            val inputs = splits[random.nextInt(splits.size)].map { percent(BigDecimal(it)) }

            val result = DeductionCalculator.calculate(gross, inputs, converter)

            assertInvariants("round $round gross $gross inputs ${describe(inputs)}", gross, result)
        }
    }

    @Test
    fun mixedInputsAreRefusedExactlyWhenTheExactTotalIsAboveTheGross() {
        var accepted = 0
        var refused = 0
        repeat(ITERATIONS) { round ->
            val gross = randomGross()
            val inputs = randomMixed()
            val label = "round $round gross $gross inputs ${describe(inputs)}"

            val outcome = runCatching { DeductionCalculator.calculate(gross, inputs, converter) }

            if (exactTotal(gross, inputs) > gross.amount) {
                assertTrue("$label: ${outcome.exceptionOrNull()}", outcome.exceptionOrNull() is WalletException.DeductionsExceedIncome)
                refused++
            } else {
                assertInvariants(label, gross, outcome.getOrThrow())
                accepted++
            }
        }
        assertTrue("both outcomes must occur: accepted $accepted refused $refused", accepted > 0 && refused > 0)
    }

    private fun assertInvariants(label: String, gross: Money, result: DeductionResult) {
        val total = result.lines.fold(Money.zero("USD")) { sum, line -> sum + line.amount }
        val paidNow = result.lines.filter { it.input.timing == DeductionTiming.NOW }
            .fold(Money.zero("USD")) { sum, line -> sum + line.amount }

        assertTrue("$label: lines $total exceed gross", total <= gross)
        assertTrue("$label: a line is negative", result.lines.none { it.amount.isNegative })
        assertEquals("$label: paid now", paidNow, result.paidNow)
        assertEquals("$label: gross split", gross, result.accountReceives + result.paidNow)
        assertTrue("$label: receipt is negative", !result.accountReceives.isNegative)
    }

    /** The unrounded total the limit is checked on: percent shares exact, fixed amounts as converted. */
    private fun exactTotal(gross: Money, inputs: List<DeductionInput>): BigDecimal =
        inputs.fold(BigDecimal.ZERO) { sum, input ->
            sum + when (input.mode) {
                DeductionMode.PERCENT -> gross.amount.multiply(input.value).divide(HUNDRED)
                DeductionMode.FIXED ->
                    converter.convert(Money.of(input.value, input.valueCurrencyCode!!), "USD").amount
            }
        }

    private fun randomGross(): Money =
        Money.of(BigDecimal.valueOf(random.nextInt(MAX_GROSS_UNITS) + 1L, random.nextInt(4)), "USD")

    /** 1 to 4 percentages in thousandths that add up to at most 100%. */
    private fun randomPercentSplit(): List<DeductionInput> {
        val count = random.nextInt(4) + 1
        val total = random.nextInt(THOUSANDTHS_IN_FULL - count + 1) + count
        val cuts = TreeSet<Int>()
        while (cuts.size < count - 1) cuts.add(random.nextInt(total - 1) + 1)
        val edges = listOf(0) + cuts + total
        return edges.zipWithNext { from, to -> percent(BigDecimal.valueOf((to - from).toLong(), 3)) }
    }

    private fun randomMixed(): List<DeductionInput> = List(random.nextInt(4) + 1) {
        if (random.nextBoolean()) {
            percent(BigDecimal.valueOf(random.nextInt(THOUSANDTHS_IN_FULL) + 1L, 3))
        } else {
            fixed(BigDecimal.valueOf(random.nextInt(MAX_FIXED_UNITS) + 1L, 2), if (random.nextBoolean()) "USD" else "EUR")
        }
    }

    private fun percent(value: BigDecimal) =
        DeductionInput(DeductionMode.PERCENT, value, null, randomTiming())

    private fun fixed(value: BigDecimal, currencyCode: String) =
        DeductionInput(DeductionMode.FIXED, value, currencyCode, randomTiming())

    private fun randomTiming() = if (random.nextBoolean()) DeductionTiming.NOW else DeductionTiming.LATER

    private fun describe(inputs: List<DeductionInput>) =
        inputs.joinToString { "${it.mode} ${it.value} ${it.valueCurrencyCode ?: ""} ${it.timing}" }

    private companion object {
        const val SEED = 20261011L
        const val ITERATIONS = 500
        const val MAX_GROSS_UNITS = 99_999_999
        const val MAX_FIXED_UNITS = 500_000
        const val THOUSANDTHS_IN_FULL = 100_000
        val HUNDRED = BigDecimal("100")
    }
}
