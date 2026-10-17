package org.aristonis.mywallet.ui.transaction

import org.aristonis.mywallet.data.format.MoneyParser
import org.aristonis.mywallet.domain.model.Money
import org.aristonis.mywallet.domain.model.Transaction

/**
 * Fills the edit form from a stored row. Kept out of [EditTransactionViewModel] so the view model
 * holds the edit's flow (load, save, delete, undo) and this holds the mapping from row to fields.
 */
internal class EditFormLoader(
    private val moneyParser: MoneyParser,
    private val feeForm: EntryFeeForm,
    private val deductionForm: IncomeDeductionForm,
) {
    /**
     * Re-hydrate every form field from a stored row, resetting transient/one-shot state. A kind with
     * no form rule yet opens read-only, showing the money that moved, its date and its note.
     */
    fun populated(state: EditTransactionUiState, tx: Transaction): EditTransactionUiState {
        val rule = EntryFormRules.forEntry(tx)
            ?: return state.copyLoaded(tx, type = null,
                EntryFormInputs(amountInput = inputOf(tx.movedAmount())), readOnly = true)
        return state.copyLoaded(tx, rule.type, rule.load(tx, moneyParser), readOnly = false)
    }

    private fun inputOf(amount: Money): String = moneyParser.toInputString(amount.amount)

    private fun EditTransactionUiState.copyLoaded(
        tx: Transaction,
        type: TransactionType?,
        inputs: EntryFormInputs,
        readOnly: Boolean,
    ) = copy(
        fee = fee.restartedFrom(feeForm.fromEntry(tx)),
        deductions = deductions.copy(drafts = if (tx is Transaction.Income) deductionForm.refresh(
            deductionForm.snapshots(tx.deductions), deductions.items, keepHistory = true,
        ) else emptyList()),
        loaded = tx,
        type = type,
        readOnly = readOnly,
        selectedAccountId = inputs.accountId,
        selectedCategoryId = inputs.categoryId,
        // Seeded here for the same reason it is written on save: the form now owns this field, so
        // leaving it unseeded would make editing anything at all clear the stored sub-category.
        selectedSubCategoryId = inputs.subCategoryId,
        destAccountId = inputs.destAccountId,
        amountInput = inputs.amountInput,
        currencyCode = inputs.currencyCode, debtId = inputs.debtId, debtSide = inputs.debtSide,
        date = tx.date,
        note = tx.note ?: "",
        isSubmitting = false,
        error = null,
        savedOn = null, savedId = null,
        deleted = false,
        undoableDelete = null,
        rateInputs = emptyMap(),
    )
}
