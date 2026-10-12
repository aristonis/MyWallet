package org.aristonis.mywallet.ui.transaction

import org.aristonis.mywallet.domain.model.Account
import org.aristonis.mywallet.domain.model.DeductionItem
import org.aristonis.mywallet.domain.model.DeductionLine
import org.aristonis.mywallet.domain.model.DeductionMode
import org.aristonis.mywallet.ui.message.UiMessage

/**
 * The deductions part of an income form. The add and edit forms hold the same state and change it
 * through the same [IncomeDeductionForm] functions, so a money rule cannot drift between them.
 *
 * @property items the enabled reusable deductions, offered without replacing this entry's edits.
 * @property itemsLoaded true once the catalog was read at least once.
 * @property itemsLoading true while a read (or a retry) of the catalog runs.
 * @property catalogError why the catalog could not be read; blocks an income save until a retry works.
 * @property drafts this entry's choices, one per offered deduction.
 * @property summary the four totals shown before saving; null when nothing can be priced yet.
 * @property lines the priced lines the save would store.
 * @property error what stops an income from being saved: a catalog failure or a line that cannot be priced.
 * @property rateFields rate fields the deductions need on top of the ones the amount already shows.
 * @property convertedInputs the text of each converted-amount field, by item id; a row without an
 *   entry here shows no converted field.
 */
data class IncomeDeductionFormState(
    val items: List<DeductionItem> = emptyList(),
    val itemsLoaded: Boolean = false,
    val itemsLoading: Boolean = true,
    val catalogError: UiMessage? = null,
    val drafts: List<IncomeDeductionDraft> = emptyList(),
    val summary: IncomeDeductionSummary? = null,
    val lines: List<DeductionLine> = emptyList(),
    val error: UiMessage? = null,
    val rateFields: List<RateField> = emptyList(),
    val convertedInputs: Map<Long, String> = emptyMap(),
) {
    /** The catalog is read, so an income saves the deductions the owner actually saw. */
    val isCatalogReady: Boolean get() = itemsLoaded && !itemsLoading

    /** An income may be saved only with a read catalog and deductions that price cleanly. */
    val allowsIncomeSave: Boolean get() = isCatalogReady && error == null

    /** A retry starts: keep the owner's choices, drop the old failure. */
    fun retrying(): IncomeDeductionFormState = copy(itemsLoading = true, catalogError = null)

    /** The catalog read failed; the failure blocks an income save until a retry works. */
    fun catalogFailed(message: UiMessage): IncomeDeductionFormState =
        copy(itemsLoading = false, catalogError = message)
}

/** One change the owner makes to one entry's deduction row; [IncomeDeductionForm.edit] applies it. */
sealed interface DeductionEdit {
    /** Ticks or unticks the deduction for this entry. */
    data class SetSelected(val selected: Boolean) : DeductionEdit

    /** Switches between a percentage of the income and a fixed amount. */
    data class SetMode(val mode: DeductionMode) : DeductionEdit

    /** The percentage or the fixed amount, as typed. */
    data class SetValue(val text: String) : DeductionEdit

    /** The currency a fixed amount is given in. */
    data class SetCurrency(val code: String) : DeductionEdit

    /** The fixed amount in the income's currency, typed over the converted one. */
    data class SetConverted(val text: String) : DeductionEdit
}

/** The currency of the account with [id]; null before an account is picked. */
internal fun List<Account>.currencyOf(id: Long?): String? =
    firstOrNull { it.id == id }?.currencyCode
