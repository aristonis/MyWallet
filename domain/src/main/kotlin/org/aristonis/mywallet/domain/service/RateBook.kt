package org.aristonis.mywallet.domain.service

import org.aristonis.mywallet.domain.ledger.Leg
import org.aristonis.mywallet.domain.ledger.LegTarget
import org.aristonis.mywallet.domain.model.FxSnapshot
import java.math.BigDecimal

/**
 * The one place that answers "what is 1 unit of this currency worth in the base?".
 *
 * A form previewing a total, the save that stamps a rate on each category leg and a repayment that
 * prices a debt all ask that question. If each answered it on its own they could drift apart: the
 * form would show one figure and the saved entry would hold another, and in Tracking that shows up
 * as an expense that does not match what the user saw when recording it. Asking here keeps them in
 * step, in a fixed order:
 * 1. the base currency is always 1, even if a rate was saved or typed for it;
 * 2. a rate typed on the form for this entry;
 * 3. the saved rate;
 * 4. otherwise no rate at all. A guessed rate would put a wrong amount in the books with nothing
 *    to show it is wrong; a missing one can be flagged and filled in later.
 *
 * A typed rate never changes the saved rates. [fx] is read, never written, and every result is a new
 * value. Whether a typed rate is also saved for later entries is the save path's call, made from the
 * "Save rates I enter" setting. With that setting off, the typed rate stays on this entry only.
 *
 * @param fx The base currency, the saved rates and the currencies' decimal places, read together.
 * @param overrides Currency code to a rate typed on the form, as "value of 1 unit in the base".
 * @throws IllegalArgumentException when a typed rate is zero or less.
 */
class RateBook(
    private val fx: FxSnapshot,
    private val overrides: Map<String, BigDecimal> = emptyMap(),
) {

    init {
        // A rate of zero or less would price every amount in that currency at nothing or below it.
        overrides.forEach { (code, rate) ->
            require(rate.signum() > 0) { "the typed rate for $code must be above zero, was ${rate.toPlainString()}" }
        }
    }

    /** Value of 1 unit of [code] in the base: base 1, then typed, then saved, else null. */
    fun rateToBase(code: String): BigDecimal? = when (code) {
        fx.baseCurrencyCode -> BigDecimal.ONE
        else -> overrides[code] ?: fx.ratesToBase[code]
    }

    /**
     * The "1 unit of [from] = X units of [to]" rate, routed through the base and rounded the same
     * way a transfer stores it (see [CurrencyConverter.sourceToDestRate]). Null when either side has
     * no rate.
     */
    fun pairRate(from: String, to: String): BigDecimal? {
        val fromRate = rateToBase(from) ?: return null
        val toRate = rateToBase(to) ?: return null
        return fromRate.divide(toRate, CurrencyConverter.RATE_SCALE, CurrencyConverter.ROUNDING)
    }

    /**
     * A converter that prices with the typed rates as well as the saved ones. A typed rate replaces
     * the saved rate for its currency; the base stays 1 inside [CurrencyConverter] itself.
     */
    fun converter(): CurrencyConverter =
        CurrencyConverter(
            baseCurrencyCode = fx.baseCurrencyCode,
            ratesToBase = fx.ratesToBase + overrides,
            decimalPlaces = fx.decimalPlaces,
        )

    /**
     * Returns new legs with a rate on each category leg that has none.
     *
     * Only category legs carry a rate, because only they are converted for Tracking; account and
     * debt legs come back unchanged. A category leg that already has a rate keeps it, so an entry
     * keeps the rate it was recorded with. A currency with no rate leaves the leg's rate null.
     */
    fun stamp(legs: List<Leg>): List<Leg> = legs.map(::stampLeg)

    /**
     * Returns new legs for an edited entry, rated against the legs it had before the edit ([old]).
     *
     * This is the one place the edit-time rate rule lives, so no caller can carry an old currency's
     * rate onto a leg that is now in another currency. A category leg keeps its rate only when the
     * old entry had a leg with the same role and position, in the same currency, and no rate was
     * typed for that currency on this edit. Otherwise it gets [rateToBase] for its currency: base 1,
     * then typed, then saved, else null. Account and debt legs come back unchanged.
     */
    fun restamp(legs: List<Leg>, old: List<Leg>): List<Leg> {
        val oldBySlot = old.associateBy { it.role to it.position }
        return legs.map { leg -> restampLeg(leg, oldBySlot[leg.role to leg.position]) }
    }

    private fun restampLeg(leg: Leg, oldLeg: Leg?): Leg {
        if (leg.target !is LegTarget.Category) return leg
        val currency = leg.amount.currencyCode
        val keepsOldRate = oldLeg != null &&
            oldLeg.amount.currencyCode == currency &&
            currency !in overrides
        return if (keepsOldRate) leg else leg.copy(rateToBase = rateToBase(currency))
    }

    private fun stampLeg(leg: Leg): Leg {
        if (leg.target !is LegTarget.Category || leg.rateToBase != null) return leg
        return leg.copy(rateToBase = rateToBase(leg.amount.currencyCode))
    }
}
