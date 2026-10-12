package org.aristonis.mywallet.ui.transaction

import kotlinx.coroutines.CancellationException
import org.aristonis.mywallet.data.format.MoneyParseException
import org.aristonis.mywallet.data.format.MoneyParser
import org.aristonis.mywallet.di.ErrorReporter
import org.aristonis.mywallet.domain.error.WalletException
import org.aristonis.mywallet.domain.model.DeductionInput
import org.aristonis.mywallet.domain.model.DeductionItem
import org.aristonis.mywallet.domain.model.DeductionLine
import org.aristonis.mywallet.domain.model.DeductionMode
import org.aristonis.mywallet.domain.model.DeductionTiming
import org.aristonis.mywallet.domain.model.Money
import org.aristonis.mywallet.domain.model.Transaction
import org.aristonis.mywallet.domain.service.DeductionCalculator
import org.aristonis.mywallet.domain.service.RateBook
import org.aristonis.mywallet.ui.message.RefusedInputException
import org.aristonis.mywallet.ui.message.UiMessage
import org.aristonis.mywallet.ui.message.toUiMessage

/**
 * The deduction rules both income forms share: which rows are offered, how an owner's edit changes a
 * row, how the rows are priced and what the save stores. Parses entry choices and delegates every
 * conversion and rounding to the domain calculator. Pure apart from reporting an unexpected failure.
 */
internal class IncomeDeductionForm(
    private val parser: MoneyParser,
    private val errors: ErrorReporter = ErrorReporter { _, _ -> },
) {
    fun defaults(items: List<DeductionItem>, category: Long?): List<IncomeDeductionDraft> = items
        .filter { it.enabled }.sortedWith(compareBy<DeductionItem> { it.sortOrder }.thenBy { it.id })
        .map { item -> IncomeDeductionDraft(item.id, item.name, item.expenseCategoryId, item.mode,
            parser.toInputString(item.value), item.valueCurrencyCode, item.timing,
            category != null && category in item.autoAddCategoryIds) }

    fun snapshots(lines: List<DeductionLine>): List<IncomeDeductionDraft> = lines.sortedBy { it.position }
        .map { line -> IncomeDeductionDraft(line.itemId, line.name, line.categoryId, line.mode,
            parser.toInputString(line.value), line.valueCurrencyCode, line.timing, true, historical = line) }

    fun refresh(rows: List<IncomeDeductionDraft>, items: List<DeductionItem>, keepHistory: Boolean) =
        rows.filter { row -> (keepHistory && row.historical != null) ||
            items.any { it.id == row.itemId && it.enabled } } +
            defaults(items, null).filter { item -> rows.none { it.itemId == item.itemId } }

    /**
     * The catalog emitted [items]. A new income takes the defaults of its category the first time,
     * then keeps the owner's choices; an edited income keeps its recorded lines ([keepHistory]).
     */
    fun withCatalog(
        state: IncomeDeductionFormState, items: List<DeductionItem>, isIncome: Boolean,
        category: Long?, keepHistory: Boolean,
    ): IncomeDeductionFormState {
        val rows = when {
            !isIncome -> emptyList()
            !keepHistory && !state.itemsLoaded -> defaults(items, category)
            else -> refresh(state.drafts, items, keepHistory)
        }
        return state.copy(items = items.filter { it.enabled }, itemsLoaded = true, itemsLoading = false,
            catalogError = null, drafts = rows)
    }

    /** The rows a fresh income form starts with: the catalog defaults for [category], none for other kinds. */
    fun freshDrafts(state: IncomeDeductionFormState, isIncome: Boolean, category: Long?): List<IncomeDeductionDraft> =
        if (isIncome) defaults(state.items, category) else emptyList()

    /** Applies one owner [change] to the row of [itemId]; [currency] is the income's currency. */
    fun edit(
        rows: List<IncomeDeductionDraft>, itemId: Long, change: DeductionEdit, currency: String?,
    ): List<IncomeDeductionDraft> {
        val row = rows.firstOrNull { it.itemId == itemId } ?: return rows
        val draft = when (change) {
            is DeductionEdit.SetSelected -> row.copy(selected = change.selected)
            // A fixed amount needs a currency; the income's own is the one that needs no rate.
            is DeductionEdit.SetMode -> row.copy(mode = change.mode, valueCurrencyCode =
                if (change.mode == DeductionMode.FIXED) row.valueCurrencyCode ?: currency else null)
            is DeductionEdit.SetValue -> row.copy(valueInput = change.text)
            is DeductionEdit.SetCurrency -> row.copy(valueCurrencyCode = change.code)
            is DeductionEdit.SetConverted -> row.copy(overrideInput = change.text, overrideEdited = true)
        }
        return applyChange(rows, draft)
    }

    /**
     * Applies one entry's choice without changing the reusable rule. A new value, mode or currency
     * drops a typed converted amount, which was for the old value. Any explicit edit marks the row
     * to be repriced, and the mark stays: typing a value back to the recorded one is still a choice
     * to price it now, not to keep the recorded amount.
     */
    fun applyChange(rows: List<IncomeDeductionDraft>, draft: IncomeDeductionDraft): List<IncomeDeductionDraft> =
        rows.map { row ->
            if (row.itemId != draft.itemId) row else {
                val valueChanged = row.mode != draft.mode || row.valueInput != draft.valueInput ||
                    row.valueCurrencyCode != draft.valueCurrencyCode
                row.copy(selected = draft.selected, mode = draft.mode, valueInput = draft.valueInput,
                    valueCurrencyCode = draft.valueCurrencyCode,
                    overrideInput = if (valueChanged) "" else draft.overrideInput,
                    overrideEdited = !valueChanged && draft.overrideEdited,
                    repriceFixed = row.repriceFixed || draft.repriceFixed || valueChanged ||
                        row.overrideInput != draft.overrideInput || row.overrideEdited != draft.overrideEdited)
            }
        }

    /**
     * The income moved to another currency: a converted amount typed in the old one means nothing
     * now, and every fixed line is priced afresh in the new currency.
     */
    fun onCurrencyChanged(
        rows: List<IncomeDeductionDraft>, oldCurrency: String?, newCurrency: String?,
    ): List<IncomeDeductionDraft> =
        if (oldCurrency == newCurrency) rows else rows.map {
            it.copy(overrideInput = "", overrideEdited = false, repriceFixed = true)
        }

    /** Prices [state]'s rows for the form as it is now; a non-income form shows no deductions. */
    fun recalculated(
        state: IncomeDeductionFormState, isIncome: Boolean, amount: String, currency: String?,
        rates: RateQuoteInput,
    ): IncomeDeductionFormState {
        if (!isIncome) return state.copy(summary = null, lines = emptyList(), error = null,
            rateFields = emptyList(), convertedInputs = emptyMap())
        val preview = previewOrUnexpected(state.drafts, amount, currency, rates)
        return state.copy(summary = preview.summary, lines = preview.lines,
            error = state.catalogError ?: preview.error, rateFields = rateFields(state.drafts, currency, rates),
            convertedInputs = convertedInputs(state.drafts, preview.lines, currency))
    }

    /**
     * The entry the save stores: an income carries the lines priced now, from the same inputs and
     * rates the form showed. Anything else is returned as it is.
     *
     * @throws RefusedInputException when the deductions cannot be saved as shown.
     */
    fun forSave(
        entry: Transaction, state: IncomeDeductionFormState, amount: String, rates: RateQuoteInput,
    ): Transaction {
        if (entry !is Transaction.Income) return entry
        state.error?.let { throw RefusedInputException(it) }
        val preview = calculate(state.drafts, amount, entry.amount.currencyCode, rates)
        // Saving without the lines would credit the account the full gross; refuse instead.
        preview.error?.let { throw RefusedInputException(it) }
        return entry.copy(deductions = preview.lines)
    }

    // The preview runs on every keystroke inside a state update; a bug must show, not crash the form.
    private fun previewOrUnexpected(
        rows: List<IncomeDeductionDraft>, amount: String, currency: String?, rates: RateQuoteInput,
    ): IncomeDeductionPreview = try {
        calculate(rows, amount, currency, rates)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        errors.report("Income deductions could not be calculated", e)
        IncomeDeductionPreview(error = UiMessage.Unexpected)
    }

    /**
     * The converted-amount text of each selected foreign fixed row, so the screen formats nothing.
     * Once the owner edits the field it shows what they typed, even empty, so it can be cleared to
     * type afresh; an empty field still prices at the computed amount.
     */
    private fun convertedInputs(
        rows: List<IncomeDeductionDraft>, lines: List<DeductionLine>, currency: String?,
    ): Map<Long, String> {
        if (currency == null) return emptyMap()
        return rows.filter { it.selected && it.mode == DeductionMode.FIXED && it.valueCurrencyCode != currency }
            .associate { row ->
                val computed = lines.firstOrNull { it.itemId == row.itemId }?.amount?.amount
                row.itemId to if (row.overrideEdited) row.overrideInput
                else computed?.let(parser::toInputString).orEmpty()
            }
    }

    fun calculate(
        rows: List<IncomeDeductionDraft>,
        amount: String,
        currency: String?,
        rates: RateQuoteInput,
    ): IncomeDeductionPreview {
        if (currency == null || amount.isBlank()) return IncomeDeductionPreview()
        return try {
            val gross = parser.parseAmount(amount, currency)
            val selected = rows.filter { it.selected }
            if (selected.isEmpty()) return IncomeDeductionPreview(summary = IncomeDeductionSummary(
                gross, Money.zero(currency), gross, Money.zero(currency)))
            val fx = rates.fx ?: return IncomeDeductionPreview(error = UiMessage.MissingRate(currency))
            val typed = rates.typedRatesFor(rateCodes(selected, currency), parser)
            val inputs = selected.map { input(it, currency, typed.keys) }
            val book = RateBook(shownRates(fx, rates.keptRates), typed)
            val result = DeductionCalculator.calculate(gross, inputs, book.converter())
            val lines = selected.zip(result.lines).mapIndexed { index, (draft, calculated) ->
                DeductionLine(draft.itemId, index, draft.name, draft.mode, calculated.input.value,
                    calculated.input.valueCurrencyCode, draft.timing, draft.categoryId, calculated.amount,
                    draft.historical?.debtId)
            }
            val total = lines.fold(Money.zero(currency)) { sum, line -> sum + line.amount }
            val owed = lines.filter { it.timing == DeductionTiming.LATER }
                .fold(Money.zero(currency)) { sum, line -> sum + line.amount }
            IncomeDeductionPreview(lines, IncomeDeductionSummary(gross, total, result.accountReceives, owed))
        } catch (e: Exception) {
            val message = e.toUiMessage()
            if (message == UiMessage.Unexpected) throw e
            IncomeDeductionPreview(error = message)
        }
    }

    fun rateFields(rows: List<IncomeDeductionDraft>, currency: String?, rates: RateQuoteInput): List<RateField> {
        val fx = rates.fx ?: return emptyList()
        if (currency == null) return emptyList()
        val shown = shownRates(fx, rates.keptRates)
        return rateCodes(rows.filter { it.selected }, currency)
            .distinct().filterNot { it == fx.baseCurrencyCode }.map { code ->
                val typed = rates.rateInputs[code]
                val text = typed ?: shown.ratesToBase[code]
                    ?.let { parser.toInputString(it.stripTrailingZeros()) }.orEmpty()
                val invalid = typed != null && typed.isNotBlank() &&
                    isInvalidRate(typed)
                RateField(code, text, isError = invalid)
            }
    }

    private fun isInvalidRate(text: String): Boolean = try {
        parser.parseRate(text)
        false
    } catch (e: MoneyParseException) {
        true
    }

    private fun rateCodes(rows: List<IncomeDeductionDraft>, currency: String): List<String> = rows
        .filter { it.mode == DeductionMode.FIXED && it.valueCurrencyCode != currency }
        .flatMap { listOfNotNull(it.valueCurrencyCode, currency) }

    private fun input(draft: IncomeDeductionDraft, currency: String, typed: Set<String>): DeductionInput {
        val value = parser.parseOpeningBalance(draft.valueInput, currency).amount
        val code = draft.valueCurrencyCode.takeIf { draft.mode == DeductionMode.FIXED }
        val old = draft.historical
        val unchanged = old != null && old.mode == draft.mode && old.value.compareTo(value) == 0 &&
            old.valueCurrencyCode == code && old.amount.currencyCode == currency
        // Only a rate that counts as typed (the same one the save stamps) reprices a recorded line.
        val explicitRate = (code != null && code in typed) || currency in typed
        val override = when {
            draft.overrideInput.isNotBlank() -> parser.parseOpeningBalance(draft.overrideInput, currency)
            draft.mode == DeductionMode.FIXED && unchanged && !explicitRate && !draft.repriceFixed ->
                checkNotNull(old).amount
            else -> null
        }
        if (override?.isNegative == true) throw WalletException.DeductionValueNotPositive(override.amount)
        return DeductionInput(draft.mode, value, code, draft.timing, override)
    }
}
