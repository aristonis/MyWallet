package org.aristonis.mywallet.ui.transaction

import org.aristonis.mywallet.data.format.MoneyFormatter
import org.aristonis.mywallet.data.format.MoneyParseException
import org.aristonis.mywallet.data.format.MoneyParser
import org.aristonis.mywallet.domain.model.FxSnapshot
import org.aristonis.mywallet.domain.model.Money
import org.aristonis.mywallet.domain.service.CurrencyConverter
import org.aristonis.mywallet.domain.service.RateBook
import org.aristonis.mywallet.ui.format.display
import java.math.BigDecimal
import java.math.MathContext

/**
 * One exchange rate the form shows: what 1 unit of [currencyCode] is worth in the base currency.
 *
 * [input] is the text in the field. It starts as the saved rate so an ordinary entry needs no typing,
 * and becomes whatever the user types from then on. Every rate an entry form shows can be typed
 * over: with "Save rates I enter" off, the typed rate is kept on that one entry and the saved rate is
 * left as it is. [isEditable] false renders the field read-only, for a screen that only displays a
 * rate. [isError] marks typed text that is not a positive rate, so the field says so instead of the
 * previews quietly using the saved rate behind it.
 */
data class RateField(
    val currencyCode: String,
    val input: String,
    val isEditable: Boolean = true,
    val isError: Boolean = false,
)

/**
 * "1 [from] = [rate] [to]" for a cross-currency transfer. Kept as parts rather than a sentence so the
 * screen words it from a string resource, and [rate] is already in the locale's own digits.
 */
data class PairRate(val from: String, val to: String, val rate: String)

/** Everything the form derives from the rates, recomputed whenever an input it depends on moves. */
internal data class RateQuote(
    val rateFields: List<RateField> = emptyList(),
    val baseEquivalent: String? = null,
    val pairRate: PairRate? = null,
    val receivedDisplay: String? = null,
    val rateRequired: Boolean = false,
)

/**
 * Significant digits rather than fixed decimals: a weak currency against a strong one is a rate like
 * 0.0000769231, which fixed decimals would cut to two digits and a visible error.
 */
private const val PAIR_RATE_SIGNIFICANT_DIGITS = 6
private val PAIR_RATE_PRECISION = MathContext(PAIR_RATE_SIGNIFICANT_DIGITS, CurrencyConverter.ROUNDING)

/** A money leg and whether it contributes to income or expense tracking. */
data class QuoteLeg(val currencyCode: String, val amountInput: String, val countsInTracking: Boolean)
data class QuotePair(val from: String, val to: String)

internal data class RateQuoteInput(
    val fx: FxSnapshot?,
    val legs: List<QuoteLeg>,
    val rateInputs: Map<String, String>,
    val keptRates: Map<String, BigDecimal?> = emptyMap(),
    val pair: QuotePair? = null,
)

/**
 * Works out the rate fields, the base-currency equivalent and the transfer preview from the form's
 * inputs and the saved rates. Kept apart from the view models so the money rules sit in one small
 * place, and so they stay pure: no repository, no coroutine, the same answer for the same inputs.
 *
 * Every figure is priced through [RateBook], the same rate lookup the save stamps with, so what the
 * form shows is what the entry stores.
 *
 * A field appears only for a conversion that actually happens: the account's currency for an income
 * or expense, and both currencies of a transfer only when they differ. A same-currency transfer moves
 * the amount as is, so asking for a rate there would block it for nothing.
 *
 * Once the user has typed into a field, the typed text is the rate. If it is empty or unreadable the
 * figures that depend on it are left out rather than priced at the saved rate, which would show one
 * number and record another.
 */
internal class RateQuoter(
    private val parser: MoneyParser,
    private val formatter: MoneyFormatter,
) {
    fun quote(input: RateQuoteInput): RateQuote {
        val fx = input.fx ?: return RateQuote()
        val pair = input.pair
        val tracking = input.legs.filter { it.countsInTracking }
        val codes = tracking.map { it.currencyCode } +
            if (pair != null && pair.from != pair.to) listOf(pair.from, pair.to) else emptyList()
        val shown = shownRates(fx, input.keptRates)
        val fields = codes.distinct().filterNot { it == fx.baseCurrencyCode }.map { fieldFor(input, shown, it) }
        val book = bookFor(input, shown, fields)
        val prices = Prices(fx, book)
        val pricedLeg = tracking.firstOrNull() ?: if (pair != null) input.legs.firstOrNull() else null
        val amount = pricedLeg?.let { amountOrNull(it.amountInput, it.currencyCode) }
        return RateQuote(
            rateFields = fields,
            baseEquivalent = amount?.let { prices.inBase(it) },
            pairRate = pair?.let { prices.pairRate(it.from, it.to) },
            receivedDisplay = pair?.let { p -> amount?.let { prices.received(it, p.to) } },
            rateRequired = pair != null && fields.any { book.rateToBase(it.currencyCode) == null },
        )
    }

    /**
     * The rates the user typed into fields this entry shows, parsed strictly, for the save to stamp.
     *
     * Left out, so the save decides the rate itself as if nothing were typed:
     * - a field never typed into;
     * - a field typed for a currency the entry no longer shows (the account or type changed since),
     *   so a rate nobody can see never reaches the save;
     * - a cleared field: an empty box is no rate, and the entry keeps the rate it had or takes the
     *   saved one, as an untouched field would;
     * - a value equal to the one the field was prefilled with (the entry's kept rate on an edit, else
     *   the saved rate). Retyping what was already there changes nothing, so it must not overwrite
     *   today's saved rate with an old one or fill other entries with it.
     *
     * @throws org.aristonis.mywallet.data.format.MoneyParseException when a typed rate does not read
     *   as a positive rate, so the form names the field instead of saving at a rate nobody typed.
     */
    fun typedRates(input: RateQuoteInput, fields: List<RateField>): Map<String, BigDecimal> {
        val prefill = input.fx?.let { shownRates(it, input.keptRates).ratesToBase }.orEmpty()
        return fields
            .mapNotNull { field -> input.rateInputs[field.currencyCode]?.let { field.currencyCode to it } }
            .filter { (_, text) -> text.isNotBlank() }
            .map { (code, text) -> code to parser.parseRate(text) }
            .filterNot { (code, rate) -> prefill[code]?.compareTo(rate) == 0 }
            .toMap()
    }

    /** The saved rates with an edited entry's own rates laid over them; a kept null removes the rate. */
    private fun shownRates(fx: FxSnapshot, keptRates: Map<String, BigDecimal?>): FxSnapshot {
        if (keptRates.isEmpty()) return fx
        val kept = keptRates.filterValues { it != null }.mapValues { (_, rate) -> checkNotNull(rate) }
        val none = keptRates.filterValues { it == null }.keys
        return fx.copy(ratesToBase = fx.ratesToBase - none + kept)
    }

    private fun fieldFor(input: RateQuoteInput, shown: FxSnapshot, code: String): RateField {
        val typed = input.rateInputs[code]
        val saved = shown.ratesToBase[code]?.let { parser.toInputString(it.stripTrailingZeros()) }.orEmpty()
        // An empty field is a missing rate, not a wrong one: the screen asks for it where it is needed.
        val isError = typed != null && typed.isNotBlank() && rateOrNull(typed) == null
        return RateField(code, typed ?: saved, isError = isError)
    }

    /**
     * The rates the previews use: a readable typed rate overrides; a typed field that does not read
     * as a rate has no rate at all, so nothing is priced at the saved rate hidden behind it.
     */
    private fun bookFor(input: RateQuoteInput, shown: FxSnapshot, fields: List<RateField>): RateBook {
        val typed = fields.filter { it.currencyCode in input.rateInputs }
        val overrides = typed.mapNotNull { field -> rateOrNull(field.input)?.let { field.currencyCode to it } }.toMap()
        val unreadable = typed.map { it.currencyCode }.filterNot { it in overrides }
        return RateBook(shown.copy(ratesToBase = shown.ratesToBase - unreadable.toSet()), overrides)
    }

    // A preview is computed on every keystroke, where a half-typed number is normal, not an error.
    private fun rateOrNull(text: String): BigDecimal? =
        try { parser.parseRate(text) } catch (e: MoneyParseException) { null }

    private fun amountOrNull(text: String, currencyCode: String): Money? =
        try { parser.parseAmount(text, currencyCode) } catch (e: MoneyParseException) { null }

    /**
     * Conversions at the form's rates; each answer is null when a rate it needs is missing. The
     * received amount uses the same converter as saving a transfer, so the preview is the stored
     * figure, not an estimate of it.
     */
    private inner class Prices(private val fx: FxSnapshot, private val book: RateBook) {
        private val base = fx.baseCurrencyCode
        private val converter = book.converter()

        private fun canConvert(code: String) = book.rateToBase(code) != null

        fun inBase(amount: Money): String? {
            if (amount.currencyCode == base || !canConvert(amount.currencyCode)) return null
            return formatter.display(converter.convert(amount, base), fx.currencies)
        }

        fun pairRate(from: String, to: String): PairRate? {
            if (from == to) return null
            val rate = book.pairRate(from, to) ?: return null
            return PairRate(from, to, parser.toInputString(rate.round(PAIR_RATE_PRECISION).stripTrailingZeros()))
        }

        fun received(amount: Money, to: String): String? {
            if (amount.currencyCode == to || !canConvert(amount.currencyCode) || !canConvert(to)) return null
            return formatter.display(converter.convert(amount, to), fx.currencies)
        }
    }
}
