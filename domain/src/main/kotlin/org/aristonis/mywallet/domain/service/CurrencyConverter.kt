package org.aristonis.mywallet.domain.service

import org.aristonis.mywallet.domain.error.WalletException
import org.aristonis.mywallet.domain.model.Money
import java.math.BigDecimal
import java.math.RoundingMode

/**
 * Pure FX math (no repos, no I/O) — trivially unit-testable. Converts amounts through the base
 * currency: `amount × rateToBase(from) ÷ rateToBase(to)`, rounded to the target currency's decimals.
 *
 * @param baseCurrencyCode the app base currency (its rate is implicitly 1)
 * @param ratesToBase non-base code → "value of 1 unit in the base currency"
 * @param decimalPlaces code → how many decimals to round a result in that currency to
 */
class CurrencyConverter(
    private val baseCurrencyCode: String,
    private val ratesToBase: Map<String, BigDecimal>,
    private val decimalPlaces: Map<String, Int>,
) {
    /** value of 1 unit of [code] in the base currency; base = 1; missing non-base = fail loud. */
    fun rateToBase(code: String): BigDecimal =
        if (code == baseCurrencyCode) BigDecimal.ONE
        else ratesToBase[code] ?: throw WalletException.MissingRate(code)

    /** Convert [amount] into [toCurrency], rounded to that currency's decimals. */
    fun convert(amount: Money, toCurrency: String): Money {
        if (amount.currencyCode == toCurrency) return amount
        val inBase = amount.amount.multiply(rateToBase(amount.currencyCode))
        val decimals = decimalPlaces[toCurrency] ?: DEFAULT_DECIMALS
        val result = inBase.divide(rateToBase(toCurrency), decimals, ROUNDING)
        return Money.of(result, toCurrency)
    }

    /** The applied "1 unit of [from] = X units of [to]" rate, stored on a transfer. */
    fun sourceToDestRate(from: String, to: String): BigDecimal =
        rateToBase(from).divide(rateToBase(to), RATE_SCALE, ROUNDING)

    companion object {
        val ROUNDING: RoundingMode = RoundingMode.HALF_UP
        const val DEFAULT_DECIMALS = 2
        const val RATE_SCALE = 12

        /**
         * The most decimal places any stored amount or rate may carry.
         *
         * It is a storage limit and an input limit at once, and it has to be both: a value beyond it
         * is an extreme exponent that would OOM `toPlainString()`, and a value the app itself writes
         * beyond it is one the app then refuses to let the user re-save. Anything produced for
         * storage is rounded to this before it is written.
         */
        const val MAX_STORED_SCALE = 30
    }
}
