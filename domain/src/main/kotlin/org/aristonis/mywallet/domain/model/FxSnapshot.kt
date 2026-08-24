package org.aristonis.mywallet.domain.model

import java.math.BigDecimal

/**
 * The whole conversion context, read as one thing.
 *
 * The base currency, the rates expressed against it and the currencies' decimal places are a single
 * consistent state, but they live in three tables. Reading them as three independent streams lets a
 * change to one arrive before the others: a base-currency change can be observed with the old rates
 * still in hand, and the result is a converted total that is simply wrong — rendered as an ordinary
 * amount, with nothing to indicate anything went awry. Money that is quietly wrong is worse than
 * money that fails loudly, so these three travel together or not at all.
 */
data class FxSnapshot(
    val baseCurrencyCode: String,
    val ratesToBase: Map<String, BigDecimal>,
    val currencies: List<Currency>,
) {
    /** Currency code to the number of decimals an amount in it is rounded to. */
    val decimalPlaces: Map<String, Int> get() = currencies.associate { it.code to it.decimalPlaces }

    /** True when [code] can be converted: the base itself always can, others need a stored rate. */
    fun hasRateFor(code: String): Boolean = code == baseCurrencyCode || code in ratesToBase
}
