package org.aristonis.mywallet.domain.usecase

import kotlinx.coroutines.flow.first
import org.aristonis.mywallet.domain.error.WalletException
import org.aristonis.mywallet.domain.model.ExchangeRate
import org.aristonis.mywallet.domain.port.BaseCurrencyRepository
import org.aristonis.mywallet.domain.port.CurrencyRepository
import org.aristonis.mywallet.domain.port.RateRepository
import org.aristonis.mywallet.domain.port.SettingsRepository
import org.aristonis.mywallet.domain.service.CurrencyConverter
import java.math.BigDecimal
import java.math.MathContext

/**
 * Switches the app's base currency, re-expressing every stored rate against the new one.
 *
 * Nothing recorded is rewritten. Account balances and transaction amounts stay exactly as entered,
 * in their own currencies; what changes is the layer that converts them for display. A transfer's
 * stored `rateUsed` is a ratio between two currencies, so the base cancels out of it and historical
 * rows stay correct across any number of changes.
 *
 * The arithmetic is one rule applied to every currency: `newRate(Y) = oldRate(Y) / oldRate(X)`,
 * where X is the new base. The old base is not a special case — it simply had `oldRate = 1`, being
 * the base, which is why it comes out of this holding `1 / oldRate(X)` and gains its first stored
 * rate. X itself ends with no row at all, because a base's rate is implicit.
 */
class ChangeBaseCurrency(
    private val currencies: CurrencyRepository,
    private val rates: RateRepository,
    private val settings: SettingsRepository,
    private val baseCurrency: BaseCurrencyRepository,
) {
    /**
     * @param newBaseCode the currency to switch to.
     * @param rateInCurrentBase what one unit of it is worth in the CURRENT base. Optional when a
     *   rate is already stored for it. It exists because Manage Rates only offers currencies an
     *   account actually holds, so a currency you do not yet own could never be given a rate there —
     *   and moving country, holding only old-country accounts, is the usual reason to do this at all.
     */
    suspend operator fun invoke(newBaseCode: String, rateInCurrentBase: BigDecimal? = null) {
        rateInCurrentBase?.let {
            require(it.signum() > 0) { "a rate must be greater than zero" }
        }
        val current = settings.get()
        // Return before anything else: the base has no stored rate by definition, so a no-op would
        // otherwise fail with a missing-rate error naming the currency already in use.
        if (newBaseCode == current.baseCurrencyCode) return

        currencies.findByCode(newBaseCode) ?: throw WalletException.CurrencyNotFound(newBaseCode)

        val stored = rates.observeAll().first()
        val divisor = rateInCurrentBase
            ?: stored.firstOrNull { it.currencyCode == newBaseCode }?.rateToBase
            ?: throw WalletException.MissingRate(newBaseCode)

        // The old base joins the set at its implicit rate of 1, so one loop covers every currency.
        val everything = stored.filterNot { it.currencyCode == newBaseCode } +
            ExchangeRate(current.baseCurrencyCode, BigDecimal.ONE)

        val rebased = everything.map { rate ->
            // Divide on significant digits, not on a fixed number of decimal places. A fixed scale
            // bounds the ABSOLUTE error, which is the wrong guarantee here: a very small rate over a
            // very large one would keep barely any significant digits and bake in an error measured
            // in percent rather than fractions of a cent.
            val exact = rate.rateToBase.divide(divisor, MathContext.DECIMAL128)
            // Then round to what the app is willing to store. Thirty-four decimal places is past the
            // limit the rate screen enforces, so without this every rate a change produced would come
            // back as a field the user is told is unrealistic and cannot re-save — correct numbers
            // the app refuses to accept from itself.
            val stored = if (exact.scale() > CurrencyConverter.MAX_STORED_SCALE) {
                exact.setScale(CurrencyConverter.MAX_STORED_SCALE, CurrencyConverter.ROUNDING)
            } else {
                exact
            }
            // Now reachable, and meaning what it says: two currencies far enough apart in magnitude
            // that the smaller cannot be expressed against the larger inside that limit.
            if (stored.signum() == 0) throw WalletException.RateUnderflow(rate.currencyCode)
            ExchangeRate(rate.currencyCode, stored)
        }

        baseCurrency.rebase(newBaseCode, rebased)
    }
}
