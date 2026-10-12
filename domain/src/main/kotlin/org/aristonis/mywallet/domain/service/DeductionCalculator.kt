package org.aristonis.mywallet.domain.service

import org.aristonis.mywallet.domain.error.WalletException
import org.aristonis.mywallet.domain.model.DeductionCalculationLine
import org.aristonis.mywallet.domain.model.DeductionInput
import org.aristonis.mywallet.domain.model.DeductionMode
import org.aristonis.mywallet.domain.model.DeductionResult
import org.aristonis.mywallet.domain.model.DeductionTiming
import org.aristonis.mywallet.domain.model.Money
import java.math.BigDecimal

/**
 * Calculates the income-currency amount of selected deductions.
 *
 * Each input has an exact share: gross x percent / 100, the fixed amount converted to the income
 * currency, or the owner's [DeductionInput.amountOverride] when one is given. The limit "deductions
 * cannot exceed the income" is checked on those exact shares, so 50% + 50% or 100% paid now is
 * always allowed.
 *
 * Rounding point: every share is rounded once, HALF_UP ([CurrencyConverter.ROUNDING]), to the income
 * currency's decimals. If the rounded lines add up to more than the gross, the excess comes off the
 * line that was rounded up the most (ties: the last such line), so the stored lines never exceed the
 * gross. That line can end up with more decimals than the currency, for example 100.005 for a 100%
 * line on a gross of 100.005. The account receipt is the exact gross minus the paid-now lines.
 */
object DeductionCalculator {
    private val ONE_HUNDRED = BigDecimal("100")

    fun calculate(
        gross: Money,
        deductions: List<DeductionInput>,
        converter: CurrencyConverter,
    ): DeductionResult {
        require(!gross.isNegative) { "gross income must not be negative" }
        val decimals = converter.decimalPlacesFor(gross.currencyCode)

        val exact = deductions.map { exactShare(it, gross, converter) }
        val exactTotal = exact.fold(BigDecimal.ZERO, BigDecimal::add)
        if (exactTotal > gross.amount) {
            throw WalletException.DeductionsExceedIncome(gross, Money.of(exactTotal, gross.currencyCode))
        }

        val amounts = giveBackExcess(
            exact = exact,
            rounded = exact.map { it.setScale(decimals, CurrencyConverter.ROUNDING) },
            gross = gross.amount,
        )
        val lines = deductions.zip(amounts) { input, amount ->
            DeductionCalculationLine(input, Money.of(amount, gross.currencyCode))
        }
        val paidNow = lines
            .filter { it.input.timing == DeductionTiming.NOW }
            .fold(Money.zero(gross.currencyCode)) { sum, line -> sum + line.amount }

        return DeductionResult(gross = gross, lines = lines, paidNow = paidNow, accountReceives = gross - paidNow)
    }

    private fun exactShare(input: DeductionInput, gross: Money, converter: CurrencyConverter): BigDecimal {
        input.amountOverride?.let {
            if (it.currencyCode != gross.currencyCode) {
                throw WalletException.DeductionOverrideCurrencyMismatch(it.currencyCode, gross.currencyCode)
            }
            return it.amount
        }
        return when (input.mode) {
            DeductionMode.PERCENT -> gross.amount.multiply(input.value).divide(ONE_HUNDRED)
            DeductionMode.FIXED -> converter.convert(
                Money.of(input.value, requireNotNull(input.valueCurrencyCode)),
                gross.currencyCode,
            ).amount
        }
    }

    /** Takes the rounded total's excess over [gross] off the most rounded-up line, repeating if needed. */
    private fun giveBackExcess(
        exact: List<BigDecimal>,
        rounded: List<BigDecimal>,
        gross: BigDecimal,
    ): List<BigDecimal> {
        val amounts = rounded.toMutableList()
        var excess = amounts.fold(BigDecimal.ZERO, BigDecimal::add) - gross
        while (excess.signum() > 0) {
            val index = amounts.indices
                .filter { amounts[it].signum() > 0 }
                .reduce { best, i -> if (amounts[i] - exact[i] >= amounts[best] - exact[best]) i else best }
            val taken = excess.min(amounts[index])
            amounts[index] = amounts[index] - taken
            excess -= taken
        }
        return amounts
    }
}
