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
import org.aristonis.mywallet.data.format.MoneyParser
import org.aristonis.mywallet.di.ErrorReporter
import org.aristonis.mywallet.domain.error.WalletException
import org.aristonis.mywallet.domain.model.Account
import org.aristonis.mywallet.domain.model.Category
import org.aristonis.mywallet.domain.model.DebtSide
import org.aristonis.mywallet.domain.model.FxSnapshot
import org.aristonis.mywallet.domain.model.Money
import org.aristonis.mywallet.domain.model.Transaction
import org.aristonis.mywallet.domain.model.UndoBundle
import org.aristonis.mywallet.domain.port.AccountRepository
import org.aristonis.mywallet.domain.port.CategoryRepository
import org.aristonis.mywallet.domain.port.DebtRepository
import org.aristonis.mywallet.domain.port.EntryRepository
import org.aristonis.mywallet.domain.port.FxRepository
import org.aristonis.mywallet.domain.usecase.DeleteEntry
import org.aristonis.mywallet.domain.usecase.RestoreEntries
import org.aristonis.mywallet.domain.usecase.SaveEntry
import org.aristonis.mywallet.domain.usecase.SuggestCounterparties
import org.aristonis.mywallet.ui.message.UiMessage
import org.aristonis.mywallet.ui.message.toUiMessage
import java.time.LocalDate
import javax.inject.Inject

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
    /** The date the save stored the entry under; set once by a save, cleared by acknowledging it. */
    val savedOn: LocalDate? = null,
    val deleted: Boolean = false,
    // What [EditTransactionViewModel.delete] removed, held while the Undo affordance is up so it can be
    // put back. Non-null means "deleted, but still undoable"; navigation waits until the user undoes or
    // lets it go.
    val undoableDelete: UndoBundle? = null,
    /** Rates typed on the form, by currency code; cleared on every load. */
    val rateInputs: Map<String, String> = emptyMap(),
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
    private val errors: ErrorReporter = ErrorReporter { _, _ -> },
) : ViewModel() {

    private val quoter = RateQuoter(moneyParser, moneyFormatter)
    private val _state = MutableStateFlow(EditTransactionUiState())
    val state: StateFlow<EditTransactionUiState> = _state.asStateFlow()
    private val suggestions = CounterpartySuggestions(viewModelScope, suggestCounterparties,
        onResult = { names -> _state.update { it.copy(counterpartySuggestions = names) } },
        onFailure = { e ->
            errors.report("Counterparty suggestions could not be read", e)
            _state.update { it.copy(counterpartySuggestions = emptyList()) }
        })

    private var loadJob: Job? = null

    init {
        // Feed the pickers only; the selection comes from the loaded row, never an auto pre-select.
        // Archived accounts are not transactable, so they stay out of every picker (mirrors add).
        combine(accounts.observeAll(), categories.observeAll()) { accountList, categoryList ->
            accountList.filterNot { it.archived } to categoryList
        }
            .onEach { (accountList, categoryList) ->
                mutate { it.copy(accounts = accountList, allCategories = categoryList) }
            }
            .launchIn(viewModelScope)
        fx.observeFx().onEach { snapshot -> mutate { it.copy(fx = snapshot) } }.launchIn(viewModelScope)
    }

    /** Every change that moves a price goes through here, so the rate previews never lag the inputs. */
    private fun mutate(change: (EditTransactionUiState) -> EditTransactionUiState) = _state.update { current ->
        val next = change(current)
        val quote = quoter.quote(next.quoteInput())
        next.copy(rateFields = quote.rateFields, baseEquivalent = quote.baseEquivalent)
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
                mutate { current -> current.populatedFrom(existing).copy(counterparty = debt?.counterparty.orEmpty(),
                    debtSide = debt?.side ?: current.debtSide) }
            } catch (e: CancellationException) { throw e
            } catch (e: Exception) {
                ensureActive()
                mutate { it.copy(error = e.toUiMessage()) }
            }
        }
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

    // Changing the category drops the child with it: it belongs to the old parent, so the domain
    // guard would refuse the save with nothing on screen explaining why.
    fun selectCategory(id: Long) = _state.update {
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
     * is visible where it returns to. The undoable state is cleared up front so a repeated trigger
     * can't restore twice. Submission stays locked until restore finishes, so Delete cannot race it.
     *
     * An [WalletException.EntryIdTaken] means the entry is already back at its id (an earlier undo
     * got there first), so the undo is done, not failed. Restore never overwrites, so whatever holds
     * the id is left alone.
     */
    fun undoDelete() {
        val removed = _state.value.undoableDelete ?: return
        _state.update { it.copy(undoableDelete = null, isSubmitting = true, error = null) }
        viewModelScope.launch {
            try {
                restoreEntries(removed)
                _state.update { it.copy(deleted = true) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: WalletException.EntryIdTaken) {
                _state.update { it.copy(deleted = true, error = null) }
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
        if (snapshot.undoableDelete != null) return // the row is deleted with an undo pending — don't save a ghost
        if (snapshot.isSubmitting || snapshot.savedOn != null) return
        _state.update { it.copy(isSubmitting = true, error = null) }
        viewModelScope.launch {
            try {
                val built = buildEdited(snapshot, loaded)
                val saved = saveEntry(
                    entry = built.entry,
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
                _state.update { it.copy(isSubmitting = false, error = e.toUiMessage()) }
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
                _state.update { it.copy(isSubmitting = false, error = e.toUiMessage()) }
            }
        }
    }

    /**
     * Re-hydrate every form field from a stored row, resetting transient/one-shot state. A kind with
     * no form rule yet opens read-only, showing the money that moved, its date and its note.
     */
    private fun EditTransactionUiState.populatedFrom(tx: Transaction): EditTransactionUiState {
        val rule = EntryFormRules.forEntry(tx)
            ?: return copyLoaded(tx, type = null, EntryFormInputs(amountInput = inputOf(tx.movedAmount())), readOnly = true)
        return copyLoaded(tx, rule.type, rule.load(tx, moneyParser), readOnly = false)
    }

    private fun inputOf(amount: Money): String = moneyParser.toInputString(amount.amount)

    private fun EditTransactionUiState.copyLoaded(
        tx: Transaction,
        type: TransactionType?,
        inputs: EntryFormInputs,
        readOnly: Boolean,
    ) = copy(
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
        savedOn = null,
        deleted = false,
        undoableDelete = null,
        rateInputs = emptyMap(),
    )

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
