package org.aristonis.mywallet.ui.transaction

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.aristonis.mywallet.data.format.MoneyFormatter
import org.aristonis.mywallet.data.format.MoneyParser
import org.aristonis.mywallet.di.ErrorReporter
import org.aristonis.mywallet.di.TodayProvider
import org.aristonis.mywallet.domain.model.Account
import org.aristonis.mywallet.domain.model.Category
import org.aristonis.mywallet.domain.model.DebtSide
import org.aristonis.mywallet.domain.model.FxSnapshot
import org.aristonis.mywallet.domain.port.AccountRepository
import org.aristonis.mywallet.domain.port.CategoryRepository
import org.aristonis.mywallet.domain.port.FxRepository
import org.aristonis.mywallet.domain.port.SettingsRepository
import org.aristonis.mywallet.domain.usecase.SaveEntry
import org.aristonis.mywallet.domain.usecase.SuggestCounterparties
import org.aristonis.mywallet.ui.message.UiMessage
import org.aristonis.mywallet.ui.message.toUiMessage
import java.time.LocalDate
import javax.inject.Inject

/** The entry kind the form is recording. */
enum class TransactionType { INCOME, EXPENSE, TRANSFER, BORROW, LEND, OWED_BEFORE, CREDIT_PURCHASE, EARNED_UNPAID }

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
    /** The date the save stored the entry under; set once by a save, cleared by acknowledging it. */
    val savedOn: LocalDate? = null,
    /** Rates typed on the form, by currency code; kept across type and account changes. */
    val rateInputs: Map<String, String> = emptyMap(),
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

/**
 * Records an income, expense, or transfer through [SaveEntry]. One view model, a [TransactionType]
 * selector, and the shared [EntryFormRule] of that type, which builds the matching entry. The amount is always built in the SELECTED
 * account's currency, so it matches by construction (the domain guard stays as defense). Because the
 * write goes through the same storage Home reads, recording an entry moves the balances and net
 * worth automatically; this view model never touches balance math.
 *
 * Rates: a foreign-currency entry shows its rate on the form and what the amount is worth in the base
 * currency, so a missing or stale rate is fixed here instead of in a detour through Settings. The
 * save decides every stored rate from the typed rates handed to it; whether a typed rate also becomes
 * the saved rate is the user's setting, applied by the save.
 */
@HiltViewModel
class AddTransactionViewModel @Inject constructor(
    accounts: AccountRepository,
    categories: CategoryRepository,
    private val saveEntry: SaveEntry,
    private val today: TodayProvider,
    private val moneyParser: MoneyParser,
    fx: FxRepository,
    // The save reads "Save rates I enter" itself; the form no longer needs it, since every rate field
    // takes typing either way. Kept so the injected shape stays the one callers build.
    @Suppress("UNUSED_PARAMETER") settings: SettingsRepository,
    moneyFormatter: MoneyFormatter,
    suggestCounterparties: SuggestCounterparties,
    private val errors: ErrorReporter = ErrorReporter { _, _ -> },
) : ViewModel() {

    private val quoter = RateQuoter(moneyParser, moneyFormatter)
    private val _state = MutableStateFlow(AddTransactionUiState(date = today.today()))
    val state: StateFlow<AddTransactionUiState> = _state.asStateFlow()
    private val suggestions = CounterpartySuggestions(viewModelScope, suggestCounterparties,
        onResult = { names -> _state.update { it.copy(counterpartySuggestions = names) } },
        onFailure = { e ->
            errors.report("Counterparty suggestions could not be read", e)
            _state.update { it.copy(counterpartySuggestions = emptyList()) }
        })

    init {
        combine(accounts.observeAll(), categories.observeAll()) { accountList, categoryList ->
            // Archived accounts are not transactable — keep them out of every picker (and the
            // pre-select), or money recorded against them would silently vanish from net worth.
            accountList.filterNot { it.archived } to categoryList
        }
            .onEach { (accountList, categoryList) ->
                mutate { current ->
                    current.copy(
                        accounts = accountList,
                        allCategories = categoryList,
                        // Pre-select a source account for convenience; never auto-fill the destination.
                        selectedAccountId = current.selectedAccountId ?: accountList.firstOrNull()?.id,
                    )
                }
            }
            .launchIn(viewModelScope)
        fx.observeFx()
            .onEach { snapshot ->
                mutate { it.copy(fx = snapshot, currencyCode = it.currencyCode ?: snapshot.baseCurrencyCode) }
            }
            .launchIn(viewModelScope)
    }

    /**
     * Every change goes through here so the rate previews are recomputed in the same step. Deriving
     * them later, off another flow, would leave a moment where [submit] reads a fresh amount next to
     * a stale rate field.
     */
    private fun mutate(change: (AddTransactionUiState) -> AddTransactionUiState) = _state.update { current ->
        val next = change(current)
        val quote = quoter.quote(next.quoteInput())
        next.copy(
            rateFields = quote.rateFields,
            baseEquivalent = quote.baseEquivalent,
            pairRate = quote.pairRate,
            receivedDisplay = quote.receivedDisplay,
            rateRequired = quote.rateRequired,
        )
    }

    fun selectType(type: TransactionType) = mutate {
        // Switching type clears the category: the use-cases only check a category EXISTS, not that its
        // kind matches, so a stale income category must not survive into an expense (and vice versa).
        it.copy(type = type, selectedCategoryId = null, selectedSubCategoryId = null, error = null)
    }

    fun setCounterparty(value: String) {
        mutate { it.copy(counterparty = value, counterpartySuggestions = emptyList(), error = null) }
        suggestions.request(value)
    }
    fun selectCounterpartySuggestion(value: String) {
        suggestions.invalidate()
        mutate { it.copy(counterparty = value, counterpartySuggestions = emptyList(), error = null) }
    }
    fun selectCurrency(code: String) = mutate { it.copy(currencyCode = code) }
    fun selectDebtSide(side: DebtSide) = mutate { it.copy(debtSide = side) }
    fun selectAccount(id: Long) = mutate { it.copy(selectedAccountId = id) }

    // Changing the category drops the child with it: the previous category's child would otherwise
    // ride along and be refused by the domain guard at save time.
    fun selectCategory(id: Long) = _state.update {
        it.copy(selectedCategoryId = id, selectedSubCategoryId = null)
    }

    fun selectSubCategory(id: Long?) = _state.update { it.copy(selectedSubCategoryId = id) }

    fun selectDestAccount(id: Long) = mutate { it.copy(destAccountId = id) }

    fun setAmount(text: String) = mutate { it.copy(amountInput = text) }

    fun setRate(currencyCode: String, input: String) = mutate {
        it.copy(rateInputs = it.rateInputs + (currencyCode to input))
    }

    fun setDate(date: LocalDate) = _state.update { it.copy(date = date) }

    fun setNote(text: String) = _state.update { it.copy(note = text) }

    /**
     * Clear the one-shot `savedOn` signal once the screen has acted on it, and reset the form for the
     * next entry. A retained view model would otherwise bounce a re-opened screen straight back, and
     * a stale `date` would silently post the next transaction to the wrong day. The date re-reads
     * "today"; the type and source account are kept for fast repeat entry. Typed rates are dropped:
     * with saving on they are already the saved rates, and with it off they were for that entry only.
     */
    fun acknowledgeSaved() = mutate {
        it.copy(
            savedOn = null,
            amountInput = "",
            note = "",
            selectedCategoryId = null,
            selectedSubCategoryId = null,
            destAccountId = null,
            date = today.today(),
            error = null,
            rateInputs = emptyMap(),
        )
    }

    /**
     * Saves the form once. A second call while a save runs, or after one finished but before the
     * screen acknowledged it, does nothing: `isSubmitting` is set before the save is launched, so two
     * taps in a row cannot both get through and store the entry twice.
     */
    fun submit() {
        val snapshot = _state.value
        if (snapshot.isSubmitting || snapshot.savedOn != null) return
        _state.update { it.copy(isSubmitting = true, error = null) }
        viewModelScope.launch {
            try {
                // A missing category or destination is thrown by the rule, before anything is written.
                val built = EntryFormRules.forType(snapshot.type).build(
                    inputs = snapshot.formInputs(),
                    accounts = snapshot.accounts,
                    moneyParser = moneyParser,
                    date = snapshot.date,
                    note = snapshot.note,
                )
                val saved = saveEntry(
                    entry = built.entry,
                    debt = built.debt,
                    typedRates = quoter.typedRates(snapshot.quoteInput(), snapshot.rateFields),
                )
                _state.update { it.copy(isSubmitting = false, savedOn = saved.date) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // A domain refusal, a bad amount or rate, or a missing choice names itself; anything
                // else shows as unexpected, and the form is freed so it can be tried again.
                _state.update { it.copy(isSubmitting = false, error = e.toUiMessage()) }
            }
        }
    }
}

/** What the rate previews are priced from; the edit form builds the same input from its own state. */
internal fun AddTransactionUiState.quoteInput(): RateQuoteInput {
    val rule = EntryFormRules.forType(type)
    val inputs = formInputs()
    return RateQuoteInput(fx = fx, legs = rule.quoteLegs(inputs, accounts), rateInputs = rateInputs,
        pair = rule.quotePair(inputs, accounts))
}
