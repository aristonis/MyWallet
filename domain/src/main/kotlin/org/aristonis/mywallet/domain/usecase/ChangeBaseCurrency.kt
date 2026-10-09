package org.aristonis.mywallet.domain.usecase

import kotlinx.coroutines.flow.first
import org.aristonis.mywallet.domain.error.WalletException
import org.aristonis.mywallet.domain.model.ExchangeRate
import org.aristonis.mywallet.domain.port.BaseCurrencyRepository
import org.aristonis.mywallet.domain.port.CurrencyRepository
import org.aristonis.mywallet.domain.port.LedgerQueries
import org.aristonis.mywallet.domain.port.LegRate
import org.aristonis.mywallet.domain.port.LegRateChange
import org.aristonis.mywallet.domain.port.RateRepository
import org.aristonis.mywallet.domain.port.SettingsRepository
import org.aristonis.mywallet.domain.port.TransactionRunner
import org.aristonis.mywallet.domain.service.CurrencyConverter
import java.math.BigDecimal
import java.math.MathContext

/**
 * Switches the app's base currency, re-expressing every stored rate against the new one.
 *
 * Amounts are never rewritten. Account balances and entry amounts stay exactly as entered, in their
 * own currencies; what changes is the layer that converts them. A transfer's stored rate is a ratio
 * between two currencies, so the base cancels out of it and historical rows stay correct across any
 * number of changes.
 *
 * The arithmetic is one rule applied to every currency: `newRate(Y) = oldRate(Y) / oldRate(X)`,
 * where X is the new base. The old base is not a special case — it simply had `oldRate = 1`, being
 * the base, which is why it comes out of this holding `1 / oldRate(X)` and gains its first stored
 * rate. X itself ends with no row at all, because a base's rate is implicit.
 *
 * The rates stored on category legs move by the same rule, so a past month keeps its value: 100 EUR
 * recorded at 1.25 USD counted 125 USD, and after a switch to EUR it counts 100 EUR, which is the
 * same money. A leg in the new base counts at face value, so it gets 1, a leg that had no rate yet
 * included: an amount in the base needs no rate. A leg in any other currency with no rate keeps
 * none; it still waits for the first rate saved for its currency.
 *
 * The rates and leg rates are read and rewritten in one transaction. Read outside it, a leg saved or
 * filled between the read and the write would be missing from the plan and keep a rate expressed
 * against the old base, counted wrong with nothing to show for it.
 */
class ChangeBaseCurrency(
    private val currencies: CurrencyRepository,
    private val rates: RateRepository,
    private val settings: SettingsRepository,
    private val baseCurrency: BaseCurrencyRepository,
    private val ledger: LedgerQueries,
    private val runner: TransactionRunner,
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
        // Return before anything else: the base has no stored rate by definition, so a no-op would
        // otherwise fail with a missing-rate error naming the currency already in use. Nothing is
        // opened for it either.
        if (newBaseCode == settings.get().baseCurrencyCode) return

        currencies.findByCode(newBaseCode) ?: throw WalletException.CurrencyNotFound(newBaseCode)

        runner.inTransaction { rebaseTo(newBaseCode, rateInCurrentBase) }
    }

    /** Reads everything the switch depends on and hands the rewrite over; runs inside the transaction. */
    private suspend fun rebaseTo(newBaseCode: String, rateInCurrentBase: BigDecimal?) {
        // Read again here: the check above ran before the transaction held the database.
        val current = settings.get()
        if (newBaseCode == current.baseCurrencyCode) return

        val stored = rates.observeAll().first()
        val divisor = rateInCurrentBase
            ?: stored.firstOrNull { it.currencyCode == newBaseCode }?.rateToBase
            ?: throw WalletException.MissingRate(newBaseCode)

        // The old base joins the set at its implicit rate of 1, so one loop covers every currency.
        val everything = stored.filterNot { it.currencyCode == newBaseCode } +
            ExchangeRate(current.baseCurrencyCode, BigDecimal.ONE)
        val rebased = everything.map { rate ->
            ExchangeRate(rate.currencyCode, reExpress(rate.currencyCode, rate.rateToBase, divisor))
        }
        val legRates = ledger.distinctLegRates().mapNotNull { it.changeFor(newBaseCode, divisor) }

        baseCurrency.rebase(newBaseCode, rebased, legRates)
    }

    /**
     * What legs stored at this rate hold after the switch, or null when they keep it: a leg already
     * at 1 in the new base, or a leg in another currency that has no rate yet.
     */
    private fun LegRate.changeFor(newBaseCode: String, divisor: BigDecimal): LegRateChange? {
        val from = rateToBase
        val to = when {
            currencyCode == newBaseCode -> BigDecimal.ONE
            from == null -> return null
            else -> reExpress(currencyCode, from, divisor)
        }
        if (from != null && from.compareTo(to) == 0) return null
        return LegRateChange(currencyCode, from, to)
    }

    /**
     * [rate] (one unit of [code] in the old base) expressed in the new base, whose old rate is
     * [divisor]. Rate rows and leg rates both come through here, so a leg recorded at today's rate
     * ends exactly equal to the rate row after the switch.
     */
    private fun reExpress(code: String, rate: BigDecimal, divisor: BigDecimal): BigDecimal {
        // Divide on significant digits, not on a fixed number of decimal places. A fixed scale bounds
        // the ABSOLUTE error, which is the wrong guarantee here: a very small rate over a very large
        // one would keep barely any significant digits and bake in an error measured in percent
        // rather than fractions of a cent.
        val exact = rate.divide(divisor, MathContext.DECIMAL128)
        // Then round to what the app is willing to store. Thirty-four decimal places is past the limit
        // the rate screen enforces, so without this every rate a change produced would come back as a
        // field the user is told is unrealistic and cannot re-save — correct numbers the app refuses
        // to accept from itself.
        val capped = if (exact.scale() > CurrencyConverter.MAX_STORED_SCALE) {
            exact.setScale(CurrencyConverter.MAX_STORED_SCALE, CurrencyConverter.ROUNDING)
        } else {
            exact
        }
        // Two currencies far enough apart in magnitude that the smaller cannot be expressed against
        // the larger inside that limit.
        if (capped.signum() == 0) throw WalletException.RateUnderflow(code)
        return capped
    }
}
