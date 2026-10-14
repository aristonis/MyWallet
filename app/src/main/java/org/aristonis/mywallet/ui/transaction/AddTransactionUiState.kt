package org.aristonis.mywallet.ui.transaction

import org.aristonis.mywallet.domain.model.Account
import org.aristonis.mywallet.domain.model.Category
import org.aristonis.mywallet.domain.model.DebtSide
import org.aristonis.mywallet.domain.model.FxSnapshot
import org.aristonis.mywallet.ui.message.UiMessage
import java.time.LocalDate

/** The entry kind the form is recording. */
enum class TransactionType {
    INCOME, EXPENSE, TRANSFER, REFUND, BORROW, LEND, OWED_BEFORE, CREDIT_PURCHASE, EARNED_UNPAID
}

/** Immutable snapshot the add-transaction screen renders from. */
data class AddTransactionUiState(
    val type: TransactionType = TransactionType.EXPENSE,
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
    val date: LocalDate,
    val note: String = "",
    val isSubmitting: Boolean = false,
    val error: UiMessage? = null,
    /** The submit error came from parsing the primary amount in the entry rule. */
    val errorIsAmountInput: Boolean = false,
    /** The date the save stored the entry under; set once by a save, cleared by acknowledging it. */
    val savedOn: LocalDate? = null,
    /** Rates typed on the form, by currency code; kept across type and account changes. */
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
    /** For a cross-currency transfer, what 1 unit of the source buys in the destination currency. */
    val pairRate: PairRate? = null,
    /** For a cross-currency transfer, what lands in the destination account. */
    val receivedDisplay: String? = null,
    /** A transfer cannot be priced until every currency in it has a rate, typed or saved. */
    val rateRequired: Boolean = false,
) {
    /**
     * The sub-categories offered under the chosen category. Filtered by kind as well as parent:
     * restored data can hold a child whose kind differs from its parent's, and offering one would
     * put a choice on screen that the domain then refuses, leaving Save dead with no explanation.
     */
    val subCategoriesForSelected: List<Category>
        get() = selectedCategoryId?.let { parent ->
            val parentKind = allCategories.firstOrNull { it.id == parent }?.kind
            allCategories.filter { it.parentId == parent && it.kind == parentKind }
        }.orEmpty()

    /** Categories offered for the current type: income lists income, expense lists expense, transfer none. */
    val categoriesForType: List<Category>
        get() = EntryFormRules.forType(type).categoriesOffered(allCategories)

    /** Destination candidates for a transfer: every account except the chosen source. */
    val destAccounts: List<Account>
        get() = accounts.filter { it.id != selectedAccountId }

    /**
     * False while a save runs or its date waits to be acknowledged, so one tap stores one entry. Only
     * a transfer ever needs a rate before it can be priced, so [rateRequired] is false for the others.
     */
    val canSubmit: Boolean
        get() = !isSubmitting && savedOn == null && !rateRequired &&
            (type != TransactionType.INCOME || deductions.allowsIncomeSave) &&
            (!type.hasFeeField() || fee.allowsSave) &&
            EntryFormRules.forType(type).isSubmittable(formInputs())

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

/** What the rate previews are priced from; the edit form builds the same input from its own state. */
internal fun AddTransactionUiState.quoteInput(): RateQuoteInput {
    val rule = EntryFormRules.forType(type)
    val inputs = formInputs()
    return RateQuoteInput(fx = fx, legs = rule.quoteLegs(inputs, accounts), rateInputs = rateInputs,
        pair = rule.quotePair(inputs, accounts))
}
