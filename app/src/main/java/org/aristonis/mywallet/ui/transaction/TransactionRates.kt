package org.aristonis.mywallet.ui.transaction

import org.aristonis.mywallet.data.format.MoneyFormatter
import org.aristonis.mywallet.data.format.MoneyParseException
import org.aristonis.mywallet.data.format.MoneyParser
import org.aristonis.mywallet.domain.model.FxSnapshot
import org.aristonis.mywallet.domain.model.Money
import org.aristonis.mywallet.domain.service.CurrencyConverter
import org.aristonis.mywallet.ui.format.display
import java.math.BigDecimal
import java.math.MathContext

/**
 * One exchange rate the form shows: what 1 unit of [currencyCode] is worth in the base currency.
 *
 * [input] is the text in the field. It starts as the saved rate so an ordinary entry needs no typing,
 * and becomes whatever the user types from then on. [isEditable] is false only for an income or
 * expense while saving rates is off: such an entry is never converted when recorded, so a rate typed
 * there would change nothing and would only mislead. [isError] marks typed text that is not a positive
 * rate, so the field says so instead of the previews quietly using the saved rate behind it.
 */
data class RateField(
    val currencyCode: String,
    val input: String,
    val isEditable: Boolean,
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

/**
 * Works out the rate fields, the base-currency equivalent and the transfer preview from the form's
 * inputs and the saved rates. Kept apart from the view model so the money rules sit in one small place,
 * and so they stay pure: no repository, no coroutine, the same answer for the same inputs.
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
    fun quote(state: AddTransactionUiState): RateQuote {
        val fx = state.fx ?: return RateQuote()
        val source = state.accounts.firstOrNull { it.id == state.selectedAccountId } ?: return RateQuote()
        val isTransfer = state.type == TransactionType.TRANSFER
        val destCode = if (isTransfer) state.accounts.firstOrNull { it.id == state.destAccountId }?.currencyCode else null
        val editable = isTransfer || state.saveTransactionRates
        val fields = convertedCodes(isTransfer, source.currencyCode, destCode)
            .filterNot { it == fx.baseCurrencyCode }
            .map { code -> fieldFor(state, fx, code, editable) }
        val rates = fields.mapNotNull { field -> rateFor(state, fx, field)?.let { field.currencyCode to it } }.toMap()
        val prices = Prices(fx, rates)
        val amount = amountOrNull(state.amountInput, source.currencyCode)
        return RateQuote(
            rateFields = fields,
            baseEquivalent = amount?.let { prices.inBase(it) },
            pairRate = destCode?.let { prices.pairRate(source.currencyCode, it) },
            receivedDisplay = destCode?.let { code -> amount?.let { prices.received(it, code) } },
            rateRequired = isTransfer && fields.any { it.currencyCode !in rates },
        )
    }

    /** The currencies this entry converts; a transfer with no destination yet converts nothing. */
    private fun convertedCodes(isTransfer: Boolean, sourceCode: String, destCode: String?): List<String> = when {
        !isTransfer -> listOf(sourceCode)
        destCode == null || destCode == sourceCode -> emptyList()
        else -> listOf(sourceCode, destCode)
    }

    private fun fieldFor(state: AddTransactionUiState, fx: FxSnapshot, code: String, editable: Boolean): RateField {
        val typed = if (editable) state.rateInputs[code] else null
        val saved = fx.ratesToBase[code]?.let { parser.toInputString(it.stripTrailingZeros()) }.orEmpty()
        // An empty field is a missing rate, not a wrong one: the screen asks for it where it is needed.
        val isError = typed != null && typed.isNotBlank() && rateOrNull(typed) == null
        return RateField(code, typed ?: saved, editable, isError)
    }

    private fun rateFor(state: AddTransactionUiState, fx: FxSnapshot, field: RateField): BigDecimal? {
        val typed = if (field.isEditable) state.rateInputs[field.currencyCode] else null
        return if (typed != null) rateOrNull(typed) else fx.ratesToBase[field.currencyCode]
    }

    // A preview is computed on every keystroke, where a half-typed number is normal, not an error.
    private fun rateOrNull(text: String): BigDecimal? =
        try { parser.parseRate(text) } catch (e: MoneyParseException) { null }

    private fun amountOrNull(text: String, currencyCode: String): Money? =
        try { parser.parseAmount(text, currencyCode) } catch (e: MoneyParseException) { null }

    /**
     * Conversions at the form's rates; each answer is null when a rate it needs is missing. The
     * received amount uses the same converter as recording a transfer, so the preview is the stored
     * figure, not an estimate of it.
     */
    private inner class Prices(private val fx: FxSnapshot, private val rates: Map<String, BigDecimal>) {
        private val base = fx.baseCurrencyCode
        private val converter = CurrencyConverter(base, rates, fx.decimalPlaces)

        private fun canConvert(code: String) = code == base || code in rates

        fun inBase(amount: Money): String? {
            if (amount.currencyCode == base || !canConvert(amount.currencyCode)) return null
            return formatter.display(converter.convert(amount, base), fx.currencies)
        }

        fun pairRate(from: String, to: String): PairRate? {
            if (from == to || !canConvert(from) || !canConvert(to)) return null
            val rate = converter.sourceToDestRate(from, to).round(PAIR_RATE_PRECISION).stripTrailingZeros()
            return PairRate(from, to, parser.toInputString(rate))
        }

        fun received(amount: Money, to: String): String? {
            if (amount.currencyCode == to || !canConvert(amount.currencyCode) || !canConvert(to)) return null
            return formatter.display(converter.convert(amount, to), fx.currencies)
        }
    }
}
