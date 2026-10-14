package org.aristonis.mywallet.ui.transaction

import kotlinx.coroutines.CancellationException
import org.aristonis.mywallet.data.format.MoneyFormatter
import org.aristonis.mywallet.data.format.MoneyParseException
import org.aristonis.mywallet.data.format.MoneyParser
import org.aristonis.mywallet.di.ErrorReporter
import org.aristonis.mywallet.domain.error.WalletException
import org.aristonis.mywallet.domain.model.FeeMode
import org.aristonis.mywallet.domain.model.FeeRule
import org.aristonis.mywallet.domain.model.FxSnapshot
import org.aristonis.mywallet.domain.model.Money
import org.aristonis.mywallet.domain.model.Transaction
import org.aristonis.mywallet.domain.service.CurrencyConverter
import org.aristonis.mywallet.domain.service.FeeCalculator
import org.aristonis.mywallet.domain.service.RateBook
import org.aristonis.mywallet.ui.format.display
import org.aristonis.mywallet.ui.message.RefusedInputException
import org.aristonis.mywallet.ui.message.UiMessage
import org.aristonis.mywallet.ui.message.toUiMessage

/** Shared entry fee parsing and pricing, using the same decimal calculator and converter as saving. */
internal class EntryFeeForm(
    private val parser: MoneyParser,
    private val formatter: MoneyFormatter,
    private val errors: ErrorReporter,
) {
    fun fromDefault(rule: FeeRule?): FeeFormState = FeeFormState(
        mode = rule?.mode ?: FeeMode.FIXED, valueInput = rule?.let { parser.toInputString(it.value) }.orEmpty())

    /** Stored entries have an absolute fee, so an edit never guesses its original percentage. */
    fun fromEntry(entry: Transaction): FeeFormState = when (entry) {
        is Transaction.Expense -> fromMoney(entry.fee, entry.feeCategoryId)
        is Transaction.Transfer -> fromMoney(entry.fee, entry.feeCategoryId)
        else -> FeeFormState()
    }

    private fun fromMoney(amount: Money?, categoryId: Long?) = FeeFormState(
        valueInput = amount?.let { parser.toInputString(it.amount) }.orEmpty(), categoryId = categoryId)

    fun recalculated(
        state: FeeFormState, type: TransactionType?, amountInput: String, sourceCode: String?,
        destinationCode: String?, rates: RateQuoteInput, loaded: Transaction?,
    ): FeeFormState {
        val cleared = state.copy(amount = null, accountMovement = null, error = null, errorIsFeeInput = false)
        if (!type.hasFeeField() || sourceCode == null || amountInput.isBlank()) return cleared
        // Principal input belongs to AmountField. A bad principal cannot price a fee preview, but
        // it must not make the fee control look invalid or hide the amount error on submit.
        val principal = try { parser.parseAmount(amountInput, sourceCode) }
            catch (e: MoneyParseException) { return cleared }
        // A zero fee is valid, so parse it as an opening balance and keep parse errors on FeeField.
        val feeInput = try {
            state.valueInput.takeIf(String::isNotBlank)?.let { parser.parseOpeningBalance(it, sourceCode) }
        } catch (e: MoneyParseException) {
            return cleared.copy(error = e.toUiMessage(), errorIsFeeInput = true)
        }
        return try {
            val fx = rates.fx ?: return cleared.copy(
                error = if (state.valueInput.isBlank()) null else UiMessage.RateMissing)
            val codes = rates.visibleRateCodes()
            val clearedRates = codes.filter { rates.rateInputs[it]?.isBlank() == true }.toSet()
            val shown = shownRates(fx, rates.keptRates)
            // A cleared visible transfer rate cannot price a receipt at a rate hidden behind it.
            val effective = if (type == TransactionType.TRANSFER)
                shown.copy(ratesToBase = shown.ratesToBase - clearedRates) else shown
            val book = RateBook(effective, rates.typedRatesFor(codes, parser))
            val fee = calculatedFee(state, principal, feeInput, book.converter())
            if (type == TransactionType.TRANSFER && fee != null && fee >= principal) {
                throw WalletException.FeeNotBelowAmount(principal, fee)
            }
            val movement = if (type == TransactionType.EXPENSE) principal + (fee ?: Money.zero(sourceCode))
                else received(principal - (fee ?: Money.zero(sourceCode)), destinationCode, loaded, book)
            cleared.copy(amount = fee, accountMovement = movement)
        } catch (e: CancellationException) { throw e
        } catch (e: Exception) {
            val message = e.toUiMessage()
            if (message == UiMessage.Unexpected) errors.report("Entry fee could not be calculated", e)
            cleared.copy(error = message, errorIsFeeInput = message.isFeeInputError())
        }
    }

    private fun calculatedFee(state: FeeFormState, principal: Money, value: Money?, converter: CurrencyConverter): Money? {
        if (value == null) return null
        if (value.isNegative) throw WalletException.FeeValueNegative(value)
        if (state.mode == FeeMode.FIXED) requireCurrencyDecimals(value, converter)
        return FeeCalculator.calculate(principal, FeeRule(state.mode, value.amount), converter).takeIf { it.isPositive }
    }

    /**
     * A fixed fee is money in the source currency, so a digit that currency cannot hold is refused
     * rather than rounded away: the owner would otherwise save a fee they did not type.
     */
    private fun requireCurrencyDecimals(value: Money, converter: CurrencyConverter) {
        val places = converter.decimalPlacesFor(value.currencyCode)
        if (value.amount.stripTrailingZeros().scale() > places) {
            throw RefusedInputException(UiMessage.FeeTooManyDecimals(value.currencyCode, places))
        }
    }

    private fun received(net: Money, destination: String?, loaded: Transaction?, book: RateBook): Money? {
        val code = destination ?: return null
        if (code == net.currencyCode) return net
        val converter = book.converter()
        val pair = if (loaded is Transaction.Transfer) loaded.rateUsed
            else converter.sourceToDestRate(net.currencyCode, code)
        // Saving stores the pair at twelve decimal places, then multiplies the net by that pair.
        val received = Money.of(net.amount.multiply(pair)
            .setScale(converter.decimalPlacesFor(code), CurrencyConverter.ROUNDING), code)
        if (!received.isPositive) throw WalletException.AmountRoundsToZero(code)
        return received
    }

    fun displayMovement(state: FeeFormState, fx: FxSnapshot?): String? =
        state.accountMovement?.let { formatter.display(it, fx?.currencies.orEmpty()) }

    /** A direct submit cannot bypass a refused preview or save an active fee without a read role. */
    fun forSave(entry: Transaction, state: FeeFormState): Transaction {
        if (entry !is Transaction.Expense && entry !is Transaction.Transfer) return entry
        state.error?.let { throw RefusedInputException(it) }
        if (state.amount?.isPositive == true) {
            state.roleError?.let { throw RefusedInputException(it) }
            if (state.roleLoading) throw RefusedInputException(UiMessage.Unexpected)
        }
        val category = state.categoryId.takeIf { state.amount != null }
        return when (entry) {
            is Transaction.Expense -> entry.copy(fee = state.amount, feeCategoryId = category)
            is Transaction.Transfer -> entry.copy(fee = state.amount, feeCategoryId = category)
        }
    }
}
