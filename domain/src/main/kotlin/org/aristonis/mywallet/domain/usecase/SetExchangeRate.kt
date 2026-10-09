package org.aristonis.mywallet.domain.usecase

import org.aristonis.mywallet.domain.error.WalletException
import org.aristonis.mywallet.domain.model.ExchangeRate
import org.aristonis.mywallet.domain.port.CurrencyRepository
import org.aristonis.mywallet.domain.port.LedgerQueries
import org.aristonis.mywallet.domain.port.RateRepository
import org.aristonis.mywallet.domain.port.SettingsRepository
import org.aristonis.mywallet.domain.port.TransactionRunner
import java.math.BigDecimal

/**
 * Sets (or updates) the manual exchange rate for a currency: the value of 1 unit of [currencyCode]
 * in the base currency. The currency must be one the app tracks, must not be the base currency, and
 * the rate must be positive (enforced by [ExchangeRate]); all three are checked before anything is
 * written. The base is always worth 1: a stored row for it would be read as a real rate after the
 * next base change and inflate every figure in that currency.
 *
 * Saving a rate also gives it to every category leg in that currency that has no rate yet: an entry
 * recorded before its currency had a rate waits for the first one saved. This runs on every save,
 * even one that stores the value already there, so a leg left without a rate by an older write path
 * is repaired the next time the rate is saved. A leg that already carries a rate keeps it: that is
 * the rate it was recorded with, and a later rate must not move a past month.
 *
 * The rate and the filled legs are written in one transaction. A save that keeps the rates typed on
 * its form calls this inside its own transaction, which the runner joins.
 */
class SetExchangeRate(
    private val currencies: CurrencyRepository,
    private val rates: RateRepository,
    private val ledger: LedgerQueries,
    private val runner: TransactionRunner,
    private val settings: SettingsRepository,
) {
    suspend operator fun invoke(currencyCode: String, rateToBase: BigDecimal) {
        currencies.findByCode(currencyCode)
            ?: throw WalletException.CurrencyNotFound(currencyCode)
        // Built before the write, so a rate of zero or less fails without touching anything.
        val rate = ExchangeRate(currencyCode = currencyCode, rateToBase = rateToBase)
        runner.inTransaction {
            // Read inside the transaction, so a base change cannot slip in between check and write.
            require(currencyCode != settings.get().baseCurrencyCode) {
                "$currencyCode is the base currency; its rate is always 1 and is never stored"
            }
            rates.upsert(rate)
            ledger.fillMissingRate(rate.currencyCode, rate.rateToBase)
        }
    }
}
