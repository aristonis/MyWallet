package org.aristonis.mywallet.domain.service

import org.aristonis.mywallet.domain.error.WalletException
import org.aristonis.mywallet.domain.model.FeeMode
import org.aristonis.mywallet.domain.model.FeeRule
import org.aristonis.mywallet.domain.model.Money
import java.math.BigDecimal

/** Exact-decimal fee calculation for an amount leaving an account. */
object FeeCalculator {

    /**
     * Calculates [rule] against [sent], rounding HALF_UP to the sending currency's precision.
     * A fee equal to or greater than the sent amount is refused because the transfer or expense
     * would otherwise leave no positive amount to send.
     *
     * @throws WalletException.FeeNotBelowAmount when the rounded fee is not less than [sent]
     */
    fun calculate(sent: Money, rule: FeeRule, converter: CurrencyConverter): Money {
        val exact = when (rule.mode) {
            FeeMode.PERCENT -> sent.amount.multiply(rule.value).divide(HUNDRED)
            FeeMode.FIXED -> rule.value
        }
        val fee = Money.of(
            exact.setScale(converter.decimalPlacesFor(sent.currencyCode), CurrencyConverter.ROUNDING),
            sent.currencyCode,
        )
        if (fee >= sent) throw WalletException.FeeNotBelowAmount(sent, fee)
        return fee
    }

    private val HUNDRED: BigDecimal = BigDecimal("100")
}
