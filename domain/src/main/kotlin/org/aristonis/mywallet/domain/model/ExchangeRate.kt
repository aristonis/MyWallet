package org.aristonis.mywallet.domain.model

import java.math.BigDecimal

/**
 * A manual exchange rate: the value of 1 unit of [currencyCode] in the base currency
 * (canonical direction per docs/domains/currency-fx.md). Must be > 0 (fail-loud, NFR-9).
 */
data class ExchangeRate(
    val currencyCode: String,
    val rateToBase: BigDecimal,
) {
    init {
        require(currencyCode.isNotBlank()) { "currencyCode must not be blank" }
        require(rateToBase.signum() > 0) { "rateToBase must be > 0" }
    }
}
