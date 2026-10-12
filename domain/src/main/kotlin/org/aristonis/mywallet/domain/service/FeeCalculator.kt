package org.aristonis.mywallet.domain.service

import org.aristonis.mywallet.domain.model.FeeMode
import org.aristonis.mywallet.domain.model.FeeRule
import org.aristonis.mywallet.domain.model.Money
import java.math.BigDecimal

/** Exact-decimal fee calculation for an amount leaving an account. */
object FeeCalculator {

    /**
     * Calculates [rule] against [sent], rounding HALF_UP to the sending currency's precision.
     *
     * The result is never capped. An expense adds its fee to the principal, so any size is allowed;
     * a transfer must keep something to deliver, and that limit is checked once, when it is saved.
     */
    fun calculate(sent: Money, rule: FeeRule, converter: CurrencyConverter): Money {
        val exact = when (rule.mode) {
            FeeMode.PERCENT -> sent.amount.multiply(rule.value).divide(HUNDRED)
            FeeMode.FIXED -> rule.value
        }
        return Money.of(
            exact.setScale(converter.decimalPlacesFor(sent.currencyCode), CurrencyConverter.ROUNDING),
            sent.currencyCode,
        )
    }

    private val HUNDRED: BigDecimal = BigDecimal("100")
}
