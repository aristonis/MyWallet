package org.aristonis.mywallet.ui.transaction

import org.aristonis.mywallet.domain.model.Account
import org.aristonis.mywallet.domain.model.Category
import org.aristonis.mywallet.domain.model.DebtSide
import org.aristonis.mywallet.domain.model.FxSnapshot
import org.aristonis.mywallet.domain.model.Transaction
import org.aristonis.mywallet.domain.model.UndoBundle
import org.aristonis.mywallet.ui.message.UiMessage
import java.time.LocalDate

/** Immutable snapshot the edit-transaction screen renders from. */
data class EditTransactionUiState(
    // The row as first loaded; kept so a save can preserve fields the form never exposes (a transfer's
    // locked accounts + rate). Null until [EditTransactionViewModel.load] runs.
    val loaded: Transaction? = null,
    // The kind is derived from the loaded row and is never editable — you delete and re-add to change it.
    val type: TransactionType? = null,
    val accounts: List<Account> = emptyList(),
    val allCategories: List<Category> = emptyList(),
    val selectedAccountId: Long? = null,
    val selectedCategoryId: Long? = null,
    val selectedSubCategoryId: Long? = null,
    val destAccountId: Long? = null,
    val amountInput: String = "",
    val counterparty: String = "",
    val currencyCode: String? = null,
    val debtSide: DebtSide = DebtSide.I_OWE,
    val debtId: Long? = null,
    val counterpartySuggestions: List<String> = emptyList(),
    val date: LocalDate? = null,
    val note: String = "",
    val isSubmitting: Boolean = false,
    val error: UiMessage? = null,
    /** The submit error came from parsing the primary amount in the entry rule. */
    val errorIsAmountInput: Boolean = false,
    /** The date the save stored the entry under; set once by a save, cleared by acknowledging it. */
    val savedOn: LocalDate? = null,
    val deleted: Boolean = false,
    // What [EditTransactionViewModel.delete] removed, held while the Undo affordance is up so it can be
    // put back. Non-null means "deleted, but still undoable"; navigation waits until the user undoes or
    // lets it go.
    val undoableDelete: UndoBundle? = null,
    /** Rates typed on the form, by currency code; cleared on every load. */
    val rateInputs: Map<String, String> = emptyMap(),
    /** An income's deductions; empty and unused for every other type. */
    val deductions: IncomeDeductionFormState = IncomeDeductionFormState(),
    /** Optional entry fee; ignored by kinds without a fee field. */
    val fee: FeeFormState = FeeFormState(),
    /** The saved rates and base currency the previews are priced against; null until loaded. */
    val fx: FxSnapshot? = null,
    val rateFields: List<RateField> = emptyList(),
    /** The amount in the base currency, e.g. "8.00 SYP"; null when there is nothing to convert. */
    val baseEquivalent: String? = null,
    /**
     * The loaded entry is of a kind the form cannot edit yet (a debt kind). It is shown as it is, and
     * its fields cannot be saved. Guarded deletion remains available.
     */
    val readOnly: Boolean = false,
) {
    val canEdit: Boolean get() = loaded != null && !readOnly

    val canDelete: Boolean
        get() = loaded != null && !isSubmitting && savedOn == null && undoableDelete == null

    /** Children of the chosen category, filtered by kind for the reason the add form gives. */
    val subCategoriesForSelected: List<Category>
        get() = selectedCategoryId?.let { parent ->
            val parentKind = allCategories.firstOrNull { it.id == parent }?.kind
            allCategories.filter { it.parentId == parent && it.kind == parentKind }
        }.orEmpty()

    /** Categories offered for the loaded kind: income lists income, expense lists expense, transfer none. */
    val categoriesForType: List<Category>
        get() = type?.let { EntryFormRules.forType(it).categoriesOffered(allCategories) }.orEmpty()

    /**
     * A transfer's stored rate is bound to its currency pair, so editing a transfer may only move the
     * amount/date/note — the account legs stay put. The screen uses this to lock (disable) both pickers.
     */
    val accountPickersLocked: Boolean get() = type == TransactionType.TRANSFER

    /**
     * False while a save runs, while its date waits to be acknowledged, and while a delete can still
     * be undone, so one tap writes once and a deleted entry is never saved back. Always false on a
     * read-only entry.
     */
    val canSubmit: Boolean
        get() = canEdit && undoableDelete == null && !isSubmitting && savedOn == null &&
            (type != TransactionType.INCOME || deductions.allowsIncomeSave) &&
            (!type.hasFeeField() || fee.allowsSave) &&
            type?.let { EntryFormRules.forType(it).isSubmittable(formInputs()) } == true

    /** The form's choices, in the shape the shared rule of each type reads. */
    fun formInputs(): EntryFormInputs = EntryFormInputs(
        accountId = selectedAccountId,
        categoryId = selectedCategoryId,
        subCategoryId = selectedSubCategoryId,
        destAccountId = destAccountId,
        amountInput = amountInput,
        counterparty = counterparty, currencyCode = currencyCode, debtSide = debtSide, debtId = debtId,
    )
}
