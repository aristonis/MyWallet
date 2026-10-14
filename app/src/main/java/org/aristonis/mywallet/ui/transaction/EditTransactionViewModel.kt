package org.aristonis.mywallet.ui.transaction

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.aristonis.mywallet.data.format.MoneyFormatter
import org.aristonis.mywallet.data.format.MoneyParseException
import org.aristonis.mywallet.data.format.MoneyParser
import org.aristonis.mywallet.di.ErrorReporter
import org.aristonis.mywallet.domain.error.WalletException
import org.aristonis.mywallet.domain.model.DebtSide
import org.aristonis.mywallet.domain.model.FeeMode
import org.aristonis.mywallet.domain.model.Transaction
import org.aristonis.mywallet.domain.port.AccountRepository
import org.aristonis.mywallet.domain.port.CategoryRepository
import org.aristonis.mywallet.domain.port.CategoryRoleRepository
import org.aristonis.mywallet.domain.port.DebtRepository
import org.aristonis.mywallet.domain.port.EntryRepository
import org.aristonis.mywallet.domain.port.FxRepository
import org.aristonis.mywallet.domain.port.DeductionItemRepository
import org.aristonis.mywallet.domain.usecase.DeleteEntry
import org.aristonis.mywallet.domain.usecase.RestoreEntries
import org.aristonis.mywallet.domain.usecase.SaveEntry
import org.aristonis.mywallet.domain.usecase.SuggestCounterparties
import org.aristonis.mywallet.ui.message.UiMessage
import org.aristonis.mywallet.ui.message.toUiMessage
import java.time.LocalDate
import javax.inject.Inject

/**
 * Edits or deletes one existing entry. [load] re-hydrates the form from the stored entry; [submit]
 * hands the edited entry to [SaveEntry], which re-validates it and decides its rates (a transfer is
 * re-derived at its own stored rate). [delete] removes the entry through [DeleteEntry] but keeps what
 * was removed, so [undoDelete] can put it back through [RestoreEntries]; [confirmDelete] finalizes.
 *
 * The rate fields follow the add form's rules and pricing. Picking an account in another currency
 * shows that currency's rate from the saved rates, never the rate the entry had in its old currency:
 * the save re-rates that leg the same way, so the field shows what will be stored. While the entry
 * stays in its own currency and no rate is typed, the field shows the rate the entry keeps.
 *
 * Because the write goes through the same storage Home reads, balances and the Home summary recompute via the
 * reactive read path; this view model never touches balance math.
 */
@HiltViewModel
class EditTransactionViewModel @Inject constructor(
    accounts: AccountRepository,
    categories: CategoryRepository,
    private val entries: EntryRepository,
    private val saveEntry: SaveEntry,
    private val deleteEntry: DeleteEntry,
    private val restoreEntries: RestoreEntries,
    private val moneyParser: MoneyParser,
    moneyFormatter: MoneyFormatter,
    fx: FxRepository,
    private val debts: DebtRepository,
    suggestCounterparties: SuggestCounterparties,
    private val deductionItems: DeductionItemRepository,
    categoryRoles: CategoryRoleRepository,
    private val errors: ErrorReporter = ErrorReporter { _, _ -> },
) : ViewModel() {

    private val quoter = RateQuoter(moneyParser, moneyFormatter)
    private val deductionForm = IncomeDeductionForm(moneyParser, errors)
    private val feeForm = EntryFeeForm(moneyParser, moneyFormatter, errors)
    private val loader = EditFormLoader(moneyParser, feeForm, deductionForm)
    private val _state = MutableStateFlow(EditTransactionUiState())
    val state: StateFlow<EditTransactionUiState> = _state.asStateFlow()
    private val suggestions = CounterpartySuggestions(viewModelScope, suggestCounterparties,
        onResult = { names -> _state.update { it.copy(counterpartySuggestions = names) } },
        onFailure = { e ->
            errors.report("Counterparty suggestions could not be read", e)
            _state.update { it.copy(counterpartySuggestions = emptyList()) }
        })

    private var loadJob: Job? = null

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
                it.type == TransactionType.INCOME, it.selectedCategoryId, keepHistory = true)) }
        },
        onFailure = { message -> mutate { it.copy(deductions = it.deductions.catalogFailed(message)) } })

    /** Retries catalog loading without replacing the owner's existing entry choices. */
    fun retryDeductions() {
        mutate { it.copy(deductions = it.deductions.retrying()) }
        catalog.start()
    }

    init {
        catalog.start()
        // Feed the pickers only; the selection comes from the loaded row, never an auto pre-select.
        // Archived accounts are not transactable, so they stay out of every picker (mirrors add).
        combine(accounts.observeAll(), categories.observeAll()) { accountList, categoryList ->
            accountList.filterNot { it.archived } to categoryList
        }
            .onEach { (accountList, categoryList) ->
                mutate { it.copy(accounts = accountList, allCategories = categoryList,
                    fee = it.fee.withCategories(categoryList)) }
                feeCategories.start()
            }
            .launchIn(viewModelScope)
        fx.observeFx().onEach { snapshot -> mutate { it.copy(fx = snapshot) } }.launchIn(viewModelScope)
    }

    /** Every change that moves a price goes through here, so the rate previews never lag the inputs. */
    private fun mutate(change: (EditTransactionUiState) -> EditTransactionUiState) = _state.update { current ->
        val next = change(current)
        val quote = quoter.quote(next.quoteInput())
        val deductions = deductionForm.recalculated(next.deductions, next.type == TransactionType.INCOME,
            next.amountInput, next.accounts.currencyOf(next.selectedAccountId), next.quoteInput())
        val fee = feeForm.recalculated(next.fee, next.type, next.amountInput,
            next.accounts.currencyOf(next.selectedAccountId), next.accounts.currencyOf(next.destAccountId),
            next.quoteInput(), next.loaded)
        next.copy(fee = fee, rateFields = (quote.rateFields + deductions.rateFields).distinctBy { it.currencyCode },
            deductions = deductions, baseEquivalent = quote.baseEquivalent)
    }

    /**
     * Load the entry with [id] and populate the form. A missing entry (deleted since the list was
     * shown) surfaces as a fail-loud error rather than an empty editor. Also clears the one-shot
     * save/delete signals so the retained view model can be reused for the next edit without bouncing.
     */
    fun load(id: Long) {
        loadJob?.cancel()
        suggestions.invalidate()
        // Reset identity + one-shot state before the async fetch, so a reused (retained) view model
        // never renders — or lets a Save/Delete hit — the previously loaded entry while findById runs.
        mutate {
            it.copy(
                loaded = null, type = null, isSubmitting = false, error = null,
                savedOn = null, deleted = false, undoableDelete = null, rateInputs = emptyMap(), readOnly = false,
                counterparty = "", currencyCode = null, debtId = null, counterpartySuggestions = emptyList(),
                deductions = it.deductions.copy(drafts = emptyList()),
            )
        }
        loadJob = viewModelScope.launch {
            val existing = try {
                entries.findById(id)
            } catch (e: WalletException) {
                // A stored row the app cannot read names itself instead of crashing the editor.
                ensureActive()
                mutate { it.copy(error = e.toUiMessage()) }
                return@launch
            }
            ensureActive() // a newer load() cancelled this one — don't overwrite the fresh state
            if (existing == null) {
                mutate { it.copy(error = UiMessage.TransactionNotFound) }
                return@launch
            }
            try {
                val debtId = EntryFormRules.forEntry(existing)?.load(existing, moneyParser)?.debtId
                val debt = debtId?.let { debts.findById(it) ?: throw WalletException.DebtNotFound(it) }
                ensureActive()
                mutate { current ->
                    loader.populated(current, existing).copy(counterparty = debt?.counterparty.orEmpty(),
                        debtSide = debt?.side ?: current.debtSide)
                }
            } catch (e: CancellationException) { throw e
            } catch (e: Exception) {
                ensureActive()
                mutate { it.copy(error = e.toUiMessage()) }
            }
        }
    }

    /** Applies one owner [change] to this entry's deduction row, never to the reusable rule. */
    fun editDeduction(itemId: Long, change: DeductionEdit) = mutate {
        if (it.type != TransactionType.INCOME) it else it.copy(error = null, deductions = it.deductions.copy(
            drafts = deductionForm.edit(it.deductions.drafts, itemId, change,
                it.accounts.currencyOf(it.selectedAccountId))))
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
    fun selectAccount(id: Long) = mutate {
        if (it.accountPickersLocked) return@mutate it
        it.copy(selectedAccountId = id,
            fee = it.fee.onCurrencyChanged(it.accounts.currencyOf(it.selectedAccountId), it.accounts.currencyOf(id)),
            deductions = it.deductions.copy(drafts = deductionForm.onCurrencyChanged(
            it.deductions.drafts, it.accounts.currencyOf(it.selectedAccountId), it.accounts.currencyOf(id))))
    }

    /**
     * Picks the category and drops the child with it: it belongs to the old parent, so the domain
     * guard would refuse the save with nothing on screen explaining why. An edited income keeps its
     * recorded deductions: they are what was agreed for that income, whatever its category now.
     */
    fun selectCategory(id: Long) = mutate {
        it.copy(selectedCategoryId = id, selectedSubCategoryId = null)
    }

    fun selectSubCategory(id: Long?) = _state.update { it.copy(selectedSubCategoryId = id) }

    fun setAmount(text: String) = mutate { it.copy(amountInput = text) }

    fun setRate(currencyCode: String, input: String) = mutate {
        it.copy(rateInputs = it.rateInputs + (currencyCode to input))
    }

    fun setDate(date: LocalDate) = _state.update { it.copy(date = date) }

    fun setNote(text: String) = _state.update { it.copy(note = text) }

    /** Clear the one-shot `savedOn` signal after the screen has navigated away, so a re-open won't bounce. */
    fun acknowledgeSaved() = _state.update { it.copy(savedOn = null, error = null) }

    /** Clear the one-shot `deleted` signal after the screen has navigated away. */
    fun acknowledgeDeleted() = _state.update { it.copy(deleted = false, error = null) }

    /**
     * Undo the just-completed delete: put the removed entry back at its own id, then finish. Balances
     * revert via the reactive read path; `deleted` fires so the screen leaves, and the restored entry
     * is visible where it returns to. Submission stays locked until restore finishes, so a repeated
     * trigger can't restore twice and Delete cannot race it. The undoable bundle is dropped only once
     * the restore succeeded (or found the entry already back); a refusal keeps it so the owner can retry.
     *
     * An [WalletException.EntryIdTaken] means the entry is already back at its id (an earlier undo
     * got there first), so the undo is done, not failed. Restore never overwrites, so whatever holds
     * the id is left alone.
     */
    fun undoDelete() {
        val removed = _state.value.undoableDelete ?: return
        if (_state.value.isSubmitting) return
        _state.update { it.copy(isSubmitting = true, error = null) }
        viewModelScope.launch {
            try {
                restoreEntries(removed)
                _state.update { it.copy(undoableDelete = null, deleted = true) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: WalletException.EntryIdTaken) {
                _state.update { it.copy(undoableDelete = null, deleted = true, error = null) }
            } catch (e: WalletException) {
                _state.update { it.copy(error = e.toUiMessage()) }
            } catch (e: Exception) {
                _state.update { it.copy(error = UiMessage.TransactionRestoreFailed) }
            } finally {
                _state.update { it.copy(isSubmitting = false) }
            }
        }
    }

    /**
     * No undo was taken (the snackbar dismissed or timed out): finalize the delete and let the screen
     * navigate away. The entry was already removed by [delete]; nothing more to persist.
     */
    fun confirmDelete() = _state.update { it.copy(undoableDelete = null, deleted = true) }

    /**
     * Saves the edit once. A second call while a save or delete runs, or after a save finished but
     * before the screen acknowledged it, does nothing: `isSubmitting` is set before the save is
     * launched, so two taps in a row cannot both get through.
     */
    fun submit() {
        val snapshot = _state.value
        val loaded = snapshot.loaded ?: return
        if (!snapshot.canEdit) return
        if (snapshot.type == TransactionType.INCOME && !snapshot.deductions.isCatalogReady) return
        if (snapshot.undoableDelete != null) return // the row is deleted with an undo pending — don't save a ghost
        if (snapshot.isSubmitting || snapshot.savedOn != null) return
        _state.update { it.copy(isSubmitting = true, error = null, errorIsAmountInput = false) }
        viewModelScope.launch {
            try {
                val built = try { buildEdited(snapshot, loaded) }
                    catch (e: MoneyParseException) {
                        _state.update { it.copy(isSubmitting = false, error = e.toUiMessage(), errorIsAmountInput = true) }
                        return@launch
                    }
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
                // A domain refusal (a changed transfer pair, an entry gone since it was opened), a bad
                // amount or rate, or a missing choice names itself; anything else shows as unexpected,
                // and the form is freed so it can be tried again.
                if (e.toUiMessage() == UiMessage.Unexpected) errors.report("Entry could not be saved", e)
                _state.update { it.copy(isSubmitting = false, error = e.toUiMessage(), errorIsAmountInput = false) }
            }
        }
    }

    /**
     * Remove the loaded entry, then enter the undoable state instead of finishing outright: what was
     * removed is held so [undoDelete] can put it back, and navigation waits for the screen's snackbar
     * to resolve ([undoDelete] or [confirmDelete]). Balances already drop via the reactive read path.
     */
    fun delete() {
        val snapshot = _state.value
        val loaded = snapshot.loaded ?: return
        if (!snapshot.canDelete) return
        _state.update { it.copy(isSubmitting = true, error = null) }
        viewModelScope.launch {
            try {
                val removed = deleteEntry(loaded.id)
                _state.update { it.copy(isSubmitting = false, undoableDelete = removed) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (e.toUiMessage() == UiMessage.Unexpected) errors.report("Entry could not be saved", e)
                _state.update { it.copy(isSubmitting = false, error = e.toUiMessage()) }
            }
        }
    }

    /**
     * Fold the edited form back onto the loaded row through its type's rule, which keeps its id and
     * the fields the form never exposes. Income/expense may move account/category/amount/date/note; a
     * transfer keeps its locked accounts and rate, changing only the source amount (the save
     * re-derives the destination leg). The rate an income or expense carries here is ignored: the save
     * decides it.
     */
    private fun buildEdited(snapshot: EditTransactionUiState, loaded: Transaction): BuiltEntry {
        val rule = checkNotNull(snapshot.type?.let(EntryFormRules::forType)) { "an editable entry has a type" }
        return rule.build(
            inputs = snapshot.formInputs(),
            accounts = snapshot.accounts,
            moneyParser = moneyParser,
            date = snapshot.date ?: loaded.date,
            note = snapshot.note,
            loaded = loaded,
        )
    }
}
