package org.aristonis.mywallet.domain.usecase

import org.aristonis.mywallet.domain.error.WalletException
import org.aristonis.mywallet.domain.model.ExchangeRate
import org.aristonis.mywallet.domain.port.CurrencyRepository
import org.aristonis.mywallet.domain.port.RateRepository
import java.math.BigDecimal

/**
 * Sets (or updates) the manual exchange rate for a currency: the value of 1 unit of [currencyCode]
 * in the base currency. The currency must be one the app tracks, and the rate must be positive
 * (enforced by [ExchangeRate]); both fail loud rather than storing a bad or dangling rate.
 */
class SetExchangeRate(
    private val currencies: CurrencyRepository,
    private val rates: RateRepository,
) {
    suspend operator fun invoke(currencyCode: String, rateToBase: BigDecimal) {
        currencies.findByCode(currencyCode)
            ?: throw WalletException.CurrencyNotFound(currencyCode)
        rates.upsert(ExchangeRate(currencyCode = currencyCode, rateToBase = rateToBase))
    }
}
