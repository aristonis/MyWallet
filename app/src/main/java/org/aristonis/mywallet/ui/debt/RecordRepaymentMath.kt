package org.aristonis.mywallet.ui.debt

import org.aristonis.mywallet.data.format.MoneyFormatter
import org.aristonis.mywallet.data.format.MoneyParseException
import org.aristonis.mywallet.data.format.MoneyParser
import org.aristonis.mywallet.domain.model.Money
import org.aristonis.mywallet.domain.service.CurrencyConverter
import org.aristonis.mywallet.domain.service.RateBook
import org.aristonis.mywallet.ui.format.display
import org.aristonis.mywallet.ui.message.MissingSelectionException
import org.aristonis.mywallet.ui.message.UiMessage
import org.aristonis.mywallet.ui.transaction.PairRate
import org.aristonis.mywallet.ui.transaction.QuoteLeg
import org.aristonis.mywallet.ui.transaction.QuotePair
import org.aristonis.mywallet.ui.transaction.RateQuote
import org.aristonis.mywallet.ui.transaction.RateQuoteInput
import org.aristonis.mywallet.ui.transaction.RateQuoter
import java.math.BigDecimal
import java.math.MathContext

/** What one submit hands to the use case. At most two of principal, account principal and pair rate are set. */
internal data class RepaymentPlan(
    val principal: Money?,
    val accountPrincipal: Money?,
    val pairRate: BigDecimal?,
    val interest: Money?,
    val typedRates: Map<String, BigDecimal>,
)

/** Significant digits of a shown pair rate; a weak currency against a strong one needs more than 2 decimals. */
private const val PAIR_RATE_SIGNIFICANT_DIGITS = 6
private val PAIR_RATE_PRECISION = MathContext(PAIR_RATE_SIGNIFICANT_DIGITS, CurrencyConverter.ROUNDING)

/**
 * The repayment form's arithmetic kept apart from the view model: what a submit sends, and the figures
 * the form shows. Pricing is read the way the domain prices (the stored pair rate on an edit, the book
 * on a new payment), so the preview and the saved amount agree. The form never derives a rate from the
 * two amounts it was given; the typed account amount is sent as it is.
 */
internal class RecordRepaymentMath(private val parser: MoneyParser, private val formatter: MoneyFormatter) {
    private val quoter = RateQuoter(parser, formatter)

    fun quote(state: RecordRepaymentUiState): RateQuote = quoter.quote(state.quoteInput())

    fun plan(state: RecordRepaymentUiState): RepaymentPlan {
        val debtCode = checkNotNull(state.debtCurrency)
        val accountCode = checkNotNull(state.selectedAccount).currencyCode
        val principal = when {
            state.payRest -> null
            state.principalInput.isBlank() -> Money.zero(debtCode)
            else -> parser.parseAmount(state.principalInput, debtCode)
        }
        val interest = state.interestInput.takeIf(String::isNotBlank)?.let { parser.parseAmount(it, accountCode) }
        val accountPrincipal = typedAccountPrincipal(state, debtCode, accountCode)
        if (accountPrincipal != null && state.principalInput.isBlank()) {
            throw MissingSelectionException(UiMessage.AmountMissing)
        }
        val typed = quoter.typedRates(state.quoteInput(), state.rateFields)
        val pairRate = if (accountPrincipal == null) typedPairRate(state, typed) else null
        return RepaymentPlan(principal, accountPrincipal, pairRate, interest, typed)
    }

    /** The rate the form shows between the account and the debt currency, or null when it cannot say. */
    fun pairRatePreview(state: RecordRepaymentUiState, quote: RateQuote): PairRate? {
        val account = state.selectedAccount?.currencyCode ?: return null
        val debtCode = state.debtCurrency ?: return null
        val stored = state.storedPairRate().takeIf { !state.accountAmountTouched }
        if (stored != null) return PairRate(account, debtCode, shown(stored))
        return explicitPairPreview(state, account, debtCode) ?: quote.pairRate
    }

    /** The pair currencies need no book rate when typed amounts or a stored rate price the pair. */
    fun rateRequired(state: RecordRepaymentUiState, quote: RateQuote): Boolean {
        // A stored rate on an edit prices the pair just as typed amounts do.
        val pairPriced = state.isExplicitCrossCurrency() || state.storedPairRate() != null
        if (!pairPriced) return quote.rateRequired
        val account = state.selectedAccount?.currencyCode
        return state.interestInput.isNotBlank() &&
            quote.rateFields.any { it.currencyCode == account && (it.input.isBlank() || it.isError) }
    }

    fun remainingDisplay(state: RecordRepaymentUiState): String? {
        val remaining = state.debt?.summary?.remaining ?: return null
        val currencies = state.fx?.currencies ?: return null
        return formatter.display(remaining, currencies)
    }

    /** What the chosen account moves for this payment, interest included; null when it cannot be priced. */
    fun accountMovesDisplay(state: RecordRepaymentUiState): String? {
        val account = state.selectedAccount?.currencyCode ?: return null
        val currencies = state.fx?.currencies ?: return null
        val principal = movedPrincipal(state, account) ?: return null
        val interest = state.interestInput.takeIf(String::isNotBlank)?.let { amountOrNull(it, account) }
        val total = interest?.let { principal + it } ?: principal
        return if (total.isPositive) formatter.display(total, currencies) else null
    }

    private fun typedAccountPrincipal(state: RecordRepaymentUiState, debtCode: String, accountCode: String): Money? =
        if (state.payRest || debtCode == accountCode) null else {
            state.accountPrincipalInput.takeIf(String::isNotBlank)?.let { parser.parseAmount(it, accountCode) }
        }

    /** An edit that types a rate for the pair re-prices at it; the stored rate would otherwise win. */
    private fun typedPairRate(state: RecordRepaymentUiState, typed: Map<String, BigDecimal>): BigDecimal? {
        val account = state.selectedAccount?.currencyCode ?: return null
        val debtCode = state.debtCurrency ?: return null
        val snapshot = state.fx ?: return null
        if (state.editingRepaymentId == null || account == debtCode) return null
        if (account !in typed && debtCode !in typed) return null
        return RateBook(snapshot, typed).pairRate(account, debtCode)
    }

    private fun movedPrincipal(state: RecordRepaymentUiState, account: String): Money? {
        val debtCode = state.debtCurrency ?: return null
        val principal = principalAmount(state, debtCode) ?: return Money.zero(account)
        if (!principal.isPositive) return Money.zero(account)
        if (debtCode == account) return Money.of(principal.amount, account)
        if (!state.payRest && state.accountPrincipalInput.isNotBlank()) {
            return amountOrNull(state.accountPrincipalInput, account)
        }
        val rate = pricingRate(state, account, debtCode)?.takeIf { it.signum() > 0 } ?: return null
        val places = state.fx?.decimalPlaces?.get(account) ?: CurrencyConverter.DEFAULT_DECIMALS
        return Money.of(principal.amount.divide(rate, places, CurrencyConverter.ROUNDING), account)
    }

    private fun principalAmount(state: RecordRepaymentUiState, debtCode: String): Money? = when {
        state.payRest -> state.availableRemaining()
        state.principalInput.isBlank() -> null
        else -> amountOrNull(state.principalInput, debtCode)
    }

    private fun pricingRate(state: RecordRepaymentUiState, account: String, debtCode: String): BigDecimal? {
        state.storedPairRate()?.let { return it }
        val snapshot = state.fx ?: return null
        val typed = try {
            quoter.typedRates(state.quoteInput(), state.rateFields)
        } catch (e: MoneyParseException) {
            // A half-typed rate has no price yet; the field itself already flags it.
            return null
        }
        return RateBook(snapshot, typed).pairRate(account, debtCode)
    }

    private fun explicitPairPreview(state: RecordRepaymentUiState, account: String, debtCode: String): PairRate? {
        if (!state.isExplicitCrossCurrency()) return null
        val debt = amountOrNull(state.principalInput, debtCode)
        val paid = amountOrNull(state.accountPrincipalInput, account)
        if (debt == null || paid == null || !debt.isPositive || !paid.isPositive) return null
        val rate = debt.amount.divide(paid.amount, CurrencyConverter.RATE_SCALE, CurrencyConverter.ROUNDING)
        return PairRate(account, debtCode, parser.toInputString(rate))
    }

    private fun shown(rate: BigDecimal): String =
        parser.toInputString(rate.round(PAIR_RATE_PRECISION).stripTrailingZeros())

    // A preview runs on every keystroke, where a half-typed number is normal and not an error.
    private fun amountOrNull(text: String, code: String): Money? =
        try { parser.parseAmount(text, code) } catch (e: MoneyParseException) { null }
}

/** The debt's remaining as the use case sees it: an edit gets its own old principal back first. */
internal fun RecordRepaymentUiState.availableRemaining(): Money? {
    val remaining = debt?.summary?.remaining ?: return null
    val old = loadedRepayment?.principal
    return if (old != null && old.currencyCode == remaining.currencyCode) remaining + old else remaining
}

/** The pair rate stored on the repayment being edited, while it still describes the chosen currencies. */
internal fun RecordRepaymentUiState.storedPairRate(): BigDecimal? {
    val old = loadedRepayment ?: return null
    val stored = old.pairRate ?: return null
    val account = selectedAccount?.currencyCode ?: return null
    val debtCode = debtCurrency ?: return null
    val sameCurrencies = old.accountAmount.currencyCode == account && old.principal.currencyCode == debtCode
    val rateTyped = listOf(account, debtCode).any { !rateInputs[it].isNullOrBlank() }
    return stored.takeIf { sameCurrencies && !rateTyped }
}

internal fun RecordRepaymentUiState.isExplicitCrossCurrency(): Boolean =
    !payRest && principalInput.isNotBlank() && accountPrincipalInput.isNotBlank() &&
        selectedAccount?.currencyCode != null && selectedAccount?.currencyCode != debtCurrency

internal fun RecordRepaymentUiState.quoteInput(): RateQuoteInput {
    val account = selectedAccount ?: return RateQuoteInput(null, emptyList(), emptyMap())
    val debtCode = debtCurrency ?: return RateQuoteInput(fx, emptyList(), rateInputs)
    val pair = if (account.currencyCode == debtCode) null else QuotePair(account.currencyCode, debtCode)
    return RateQuoteInput(
        fx = fx,
        legs = listOf(QuoteLeg(debtCode, principalInput, false), QuoteLeg(account.currencyCode, interestInput, true)),
        rateInputs = rateInputs,
        pair = pair,
    )
}
