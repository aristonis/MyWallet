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
import org.aristonis.mywallet.domain.model.DebtSide
import org.aristonis.mywallet.domain.model.FeeMode
import org.aristonis.mywallet.domain.port.AccountRepository
import org.aristonis.mywallet.domain.port.CategoryRepository
import org.aristonis.mywallet.domain.port.CategoryRoleRepository
import org.aristonis.mywallet.domain.port.FxRepository
import org.aristonis.mywallet.domain.port.DeductionItemRepository
import org.aristonis.mywallet.domain.port.SettingsRepository
import org.aristonis.mywallet.domain.usecase.SaveEntry
import org.aristonis.mywallet.domain.usecase.SuggestCounterparties
import org.aristonis.mywallet.ui.message.UiMessage
import org.aristonis.mywallet.ui.message.toUiMessage
import java.time.LocalDate
import javax.inject.Inject

/**
 * Records an income, expense, or transfer through [SaveEntry]. One view model, a [TransactionType]
 * selector, and the shared [EntryFormRule] of that type, which builds the matching entry. The amount
 * is always built in the selected
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
    private val deductionItems: DeductionItemRepository,
    categoryRoles: CategoryRoleRepository,
    private val errors: ErrorReporter = ErrorReporter { _, _ -> },
) : ViewModel() {

    private val quoter = RateQuoter(moneyParser, moneyFormatter)
    private val deductionForm = IncomeDeductionForm(moneyParser, errors)
    private val feeForm = EntryFeeForm(moneyParser, moneyFormatter, errors)
    private val _state = MutableStateFlow(AddTransactionUiState(date = today.today()))
    val state: StateFlow<AddTransactionUiState> = _state.asStateFlow()
    private val suggestions = CounterpartySuggestions(viewModelScope, suggestCounterparties,
        onResult = { names -> _state.update { it.copy(counterpartySuggestions = names) } },
        onFailure = { e ->
            errors.report("Counterparty suggestions could not be read", e)
            _state.update { it.copy(counterpartySuggestions = emptyList()) }
        })

    private val feeCategories = FeeCategoryReader(viewModelScope, categoryRoles, errors) { change ->
        mutate { it.copy(fee = change(it.fee, it.allCategories)) }
    }

    /** Retries a failed Fees lookup without changing the fee typed for this entry. */
    fun retryFeeCategory() = feeCategories.start()

    fun setFeeValue(value: String) = mutate { it.copy(fee = it.fee.withValue(value), error = null) }
    fun selectFeeMode(mode: FeeMode) = mutate { it.copy(fee = it.fee.withMode(mode), error = null) }
    fun selectFeeCategory(id: Long?) = mutate {
        it.fee.withCategory(id, it.allCategories)?.let { fee -> it.copy(fee = fee, error = null) } ?: it
    }

    private val catalog = DeductionCatalogReader(viewModelScope, deductionItems, errors,
        onItems = { items ->
            mutate { it.copy(deductions = deductionForm.withCatalog(it.deductions, items,
                it.type == TransactionType.INCOME, it.selectedCategoryId, keepHistory = false)) }
        },
        onFailure = { message -> mutate { it.copy(deductions = it.deductions.catalogFailed(message)) } })

    /** Retries catalog loading without replacing the owner's existing entry choices. */
    fun retryDeductions() {
        mutate { it.copy(deductions = it.deductions.retrying()) }
        catalog.start()
    }

    init {
        catalog.start()
        combine(accounts.observeAll(), categories.observeAll()) { accountList, categoryList ->
            // Archived accounts are not transactable — keep them out of every picker (and the
            // pre-select), or money recorded against them would silently vanish from Home.
            accountList.filterNot { it.archived } to categoryList
        }
            .onEach { (accountList, categoryList) ->
                mutate { current ->
                    current.copy(
                        accounts = accountList,
                        allCategories = categoryList,
                        // Pre-select a source account for convenience; never auto-fill the destination.
                        selectedAccountId = current.selectedAccountId ?: accountList.firstOrNull()?.id,
                    ).withPreselectedSourceFee(current.selectedAccountId)
                        .let { updated -> updated.copy(fee = updated.fee.withCategories(categoryList)) }
                }
                feeCategories.start()
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
        val rates = next.quoteInput()
        val deductions = deductionForm.recalculated(next.deductions, next.type == TransactionType.INCOME,
            next.amountInput, next.accounts.currencyOf(next.selectedAccountId), rates)
        val fee = feeForm.recalculated(next.fee, next.type, next.amountInput,
            next.accounts.currencyOf(next.selectedAccountId), next.accounts.currencyOf(next.destAccountId),
            rates, null)
        val quote = quoter.quote(if (next.type == TransactionType.TRANSFER) rates.withFee(fee.amount) else rates)
        next.copy(
            fee = fee,
            rateFields = (quote.rateFields + deductions.rateFields).distinctBy { it.currencyCode },
            deductions = deductions,
            baseEquivalent = quote.baseEquivalent,
            pairRate = quote.pairRate,
            receivedDisplay = if (next.type == TransactionType.TRANSFER) feeForm.displayMovement(fee, next.fx)
                else quote.receivedDisplay,
            rateRequired = quote.rateRequired,
        )
    }

    /** Applies one owner [change] to this entry's deduction row, never to the reusable rule. */
    fun editDeduction(itemId: Long, change: DeductionEdit) = mutate {
        if (it.type != TransactionType.INCOME) it else it.copy(error = null, deductions = it.deductions.copy(
            drafts = deductionForm.edit(it.deductions.drafts, itemId, change,
                it.accounts.currencyOf(it.selectedAccountId))))
    }

    fun selectType(type: TransactionType) = mutate {
        // Switching type clears the category: the use-cases only check a category EXISTS, not that its
        // kind matches, so a stale income category must not survive into an expense (and vice versa).
        if (it.type == type) return@mutate it
        it.copy(type = type, fee = it.fee.restartedFrom(it.feeDefaultFor(type, it.selectedAccountId)),
            selectedCategoryId = null, selectedSubCategoryId = null, error = null,
            deductions = it.deductions.copy(
                drafts = deductionForm.freshDrafts(it.deductions, type == TransactionType.INCOME, null)))
    }

    /** A new transfer starts from its source account's default fee; other kinds start with none. */
    private fun AddTransactionUiState.feeDefaultFor(type: TransactionType, accountId: Long?): FeeFormState =
        feeForm.fromDefault(if (type == TransactionType.TRANSFER)
            accounts.firstOrNull { account -> account.id == accountId }?.defaultFee else null)

    /**
     * Transfer may be chosen before the accounts arrive; the source picked on arrival then supplies
     * the default fee, unless the owner already set one.
     */
    private fun AddTransactionUiState.withPreselectedSourceFee(previousSource: Long?): AddTransactionUiState =
        if (previousSource != null || type != TransactionType.TRANSFER) this
        else copy(fee = fee.followingSource(feeDefaultFor(type, selectedAccountId), null, null))

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
    fun selectAccount(id: Long) = mutate {
        if (it.selectedAccountId == id) return@mutate it
        val oldCode = it.accounts.currencyOf(it.selectedAccountId)
        val newCode = it.accounts.currencyOf(id)
        val fee = if (it.type == TransactionType.TRANSFER) {
            it.fee.followingSource(it.feeDefaultFor(it.type, id), oldCode, newCode)
        } else it.fee.onCurrencyChanged(oldCode, newCode)
        it.copy(selectedAccountId = id, fee = fee,
            deductions = it.deductions.copy(drafts = deductionForm.onCurrencyChanged(
            it.deductions.drafts, it.accounts.currencyOf(it.selectedAccountId), it.accounts.currencyOf(id))))
    }

    /**
     * Picks the category and drops the child with it: the previous category's child would otherwise
     * ride along and be refused by the domain guard at save time.
     *
     * On an income the deductions follow the category: picking a different one replaces the rows
     * with that category's defaults (Salary and Freelance carry different deductions), so ticks and
     * values typed for the old category are dropped. Picking the same category again keeps them.
     */
    fun selectCategory(id: Long) = mutate {
        val drafts = if (it.selectedCategoryId == id) it.deductions.drafts
            else deductionForm.freshDrafts(it.deductions, it.type == TransactionType.INCOME, id)
        it.copy(selectedCategoryId = id, selectedSubCategoryId = null, deductions = it.deductions.copy(drafts = drafts))
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
            fee = it.fee.restartedFrom(it.feeDefaultFor(it.type, it.selectedAccountId)),
            deductions = it.deductions.copy(
                drafts = deductionForm.freshDrafts(it.deductions, it.type == TransactionType.INCOME, null)),
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
        if (snapshot.type == TransactionType.INCOME && !snapshot.deductions.isCatalogReady) return
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
                    entry = feeForm.forSave(deductionForm.forSave(built.entry, snapshot.deductions,
                        snapshot.amountInput, snapshot.quoteInput()), snapshot.fee),
                    debt = built.debt,
                    typedRates = quoter.typedRates(snapshot.quoteInput(), snapshot.rateFields),
                )
                _state.update { it.copy(isSubmitting = false, savedOn = saved.date) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // A domain refusal, a bad amount or rate, or a missing choice names itself; anything
                // else shows as unexpected, and the form is freed so it can be tried again.
                if (e.toUiMessage() == UiMessage.Unexpected) errors.report("Entry could not be saved", e)
                _state.update { it.copy(isSubmitting = false, error = e.toUiMessage()) }
            }
        }
    }
}
