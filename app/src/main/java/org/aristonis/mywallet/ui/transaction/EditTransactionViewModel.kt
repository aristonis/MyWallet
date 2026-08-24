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
import org.aristonis.mywallet.data.format.MoneyParser
import org.aristonis.mywallet.domain.error.WalletException
import org.aristonis.mywallet.domain.model.Account
import org.aristonis.mywallet.domain.model.Category
import org.aristonis.mywallet.domain.model.CategoryKind
import org.aristonis.mywallet.domain.model.Money
import org.aristonis.mywallet.domain.model.Transaction
import org.aristonis.mywallet.domain.port.AccountRepository
import org.aristonis.mywallet.domain.port.CategoryRepository
import org.aristonis.mywallet.domain.port.TransactionRepository
import org.aristonis.mywallet.domain.usecase.DeleteTransaction
import org.aristonis.mywallet.domain.usecase.RestoreTransaction
import org.aristonis.mywallet.domain.usecase.UpdateTransaction
import java.time.LocalDate
import javax.inject.Inject

/** Immutable snapshot the edit-transaction screen renders from. */
data class EditTransactionUiState(
    // The row as first loaded; kept so a save can preserve fields the form never exposes (a transfer's
    // locked accounts + rate, an income's sub-category). Null until [EditTransactionViewModel.load] runs.
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
    val date: LocalDate? = null,
    val note: String = "",
    val isSubmitting: Boolean = false,
    val error: String? = null,
    val saved: Boolean = false,
    val deleted: Boolean = false,
    // The row just removed by [delete], held while the Undo affordance is up so it can be restored.
    // Non-null means "deleted, but still undoable" — navigation waits until the user undoes or lets it go.
    val undoableDelete: Transaction? = null,
) {
    /** Categories offered for the loaded kind: income lists income, expense lists expense, transfer none. */
    /** Children of the chosen category, filtered by kind for the reason the add form gives. */
    val subCategoriesForSelected: List<Category>
        get() = selectedCategoryId?.let { parent ->
            val parentKind = allCategories.firstOrNull { it.id == parent }?.kind
            allCategories.filter { it.parentId == parent && it.kind == parentKind }
        }.orEmpty()

    val categoriesForType: List<Category>
        get() = when (type) {
            TransactionType.INCOME -> allCategories.filter { it.kind == CategoryKind.INCOME && !it.isSubCategory }
            TransactionType.EXPENSE -> allCategories.filter { it.kind == CategoryKind.EXPENSE && !it.isSubCategory }
            else -> emptyList()
        }

    /**
     * A transfer's stored rate is bound to its currency pair, so editing a transfer may only move the
     * amount/date/note — the account legs stay put. The screen uses this to lock (disable) both pickers.
     */
    val accountPickersLocked: Boolean get() = type == TransactionType.TRANSFER

    val canSubmit: Boolean
        get() = undoableDelete == null && when (type) {
            null -> false
            TransactionType.TRANSFER -> amountInput.isNotBlank() && !isSubmitting
            else ->
                selectedAccountId != null && selectedCategoryId != null &&
                    amountInput.isNotBlank() && !isSubmitting
        }
}

/**
 * Edits or deletes one existing transaction. [load] re-hydrates the form from the stored row; [submit]
 * routes the edited values back through [UpdateTransaction] (which re-validates and, for a transfer,
 * re-derives the destination leg at the ORIGINAL stored rate); [delete] removes the row but keeps it
 * undoable ([undoDelete] restores it, [confirmDelete] finalizes). The type is
 * fixed at load — a transfer keeps its account legs locked so an edit can only touch amount/date/note.
 * Because the write goes through the same repositories Home reads, balances and net worth recompute for
 * free via the reactive read path; this view model never touches balance math.
 */
@HiltViewModel
class EditTransactionViewModel @Inject constructor(
    accounts: AccountRepository,
    categories: CategoryRepository,
    private val transactions: TransactionRepository,
    private val updateTransaction: UpdateTransaction,
    private val deleteTransaction: DeleteTransaction,
    private val restoreTransaction: RestoreTransaction,
    private val moneyParser: MoneyParser,
) : ViewModel() {

    private val _state = MutableStateFlow(EditTransactionUiState())
    val state: StateFlow<EditTransactionUiState> = _state.asStateFlow()

    private var loadJob: Job? = null

    init {
        // Feed the pickers only; the selection comes from the loaded row, never an auto pre-select.
        // Archived accounts are not transactable, so they stay out of every picker (mirrors add).
        combine(accounts.observeAll(), categories.observeAll()) { accountList, categoryList ->
            accountList.filterNot { it.archived } to categoryList
        }
            .onEach { (accountList, categoryList) ->
                _state.update { it.copy(accounts = accountList, allCategories = categoryList) }
            }
            .launchIn(viewModelScope)
    }

    /**
     * Load the transaction with [id] and populate the form. A missing row (deleted since the list was
     * shown) surfaces as a fail-loud error rather than an empty editor. Also clears the one-shot
     * save/delete signals so the retained view model can be reused for the next edit without bouncing.
     */
    fun load(id: Long) {
        loadJob?.cancel()
        // Reset identity + one-shot state before the async fetch, so a reused (retained) view model
        // never renders — or lets a Save/Delete hit — the previously loaded transaction while findById runs.
        _state.update {
            it.copy(
                loaded = null, type = null, isSubmitting = false, error = null,
                saved = false, deleted = false, undoableDelete = null,
            )
        }
        loadJob = viewModelScope.launch {
            val existing = transactions.findById(id)
            ensureActive() // a newer load() cancelled this one — don't overwrite the fresh state
            if (existing == null) {
                _state.update { it.copy(error = WalletException.TransactionNotFound(id).message) }
                return@launch
            }
            _state.update { it.populatedFrom(existing) }
        }
    }

    fun selectAccount(id: Long) = _state.update { it.copy(selectedAccountId = id) }

    // Changing the category drops the child with it: it belongs to the old parent, so the domain
    // guard would refuse the save with nothing on screen explaining why.
    fun selectCategory(id: Long) = _state.update {
        it.copy(selectedCategoryId = id, selectedSubCategoryId = null)
    }

    fun selectSubCategory(id: Long?) = _state.update { it.copy(selectedSubCategoryId = id) }

    fun setAmount(text: String) = _state.update { it.copy(amountInput = text) }

    fun setDate(date: LocalDate) = _state.update { it.copy(date = date) }

    fun setNote(text: String) = _state.update { it.copy(note = text) }

    /** Clear the one-shot `saved` signal after the screen has navigated away, so a re-open won't bounce. */
    fun acknowledgeSaved() = _state.update { it.copy(saved = false, error = null) }

    /** Clear the one-shot `deleted` signal after the screen has navigated away. */
    fun acknowledgeDeleted() = _state.update { it.copy(deleted = false, error = null) }

    /**
     * Undo the just-completed delete: re-add the held row, then finish. The row returns (reusing its
     * original, now-freed id) and balances revert via the reactive read path; `deleted` fires so the
     * screen returns to Home, where the restored row is visible. The undoable state is cleared up front
     * so a repeated trigger can't restore the same row twice, and a restore failure surfaces loud.
     */
    fun undoDelete() {
        val removed = _state.value.undoableDelete ?: return
        _state.update { it.copy(undoableDelete = null) }
        viewModelScope.launch {
            try {
                restoreTransaction(removed)
                _state.update { it.copy(deleted = true) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: WalletException) {
                _state.update { it.copy(error = e.message) }
            } catch (e: Exception) {
                _state.update { it.copy(error = "Couldn't restore the transaction") }
            }
        }
    }

    /**
     * No undo was taken (the snackbar dismissed or timed out): finalize the delete and let the screen
     * navigate back to Home. The row was already removed by [delete]; nothing more to persist.
     */
    fun confirmDelete() = _state.update { it.copy(undoableDelete = null, deleted = true) }

    fun submit() {
        val snapshot = _state.value
        val loaded = snapshot.loaded ?: return
        if (snapshot.undoableDelete != null) return // the row is deleted with an undo pending — don't save a ghost
        viewModelScope.launch {
            _state.update { it.copy(isSubmitting = true, error = null) }
            try {
                updateTransaction(buildEdited(snapshot, loaded))
                _state.update { it.copy(isSubmitting = false, saved = true) }
            } catch (e: WalletException) {
                // Includes TransferCurrencyPairChanged / TransactionNotFound — surface it to the user.
                _state.update { it.copy(isSubmitting = false, error = e.message) }
            } catch (e: IllegalArgumentException) {
                // Bad amount (parse) or a model invariant (amount > 0) — surface it.
                _state.update { it.copy(isSubmitting = false, error = e.message) }
            }
        }
    }

    /**
     * Remove the loaded row, then enter the undoable state instead of finishing outright: the removed
     * transaction is held so [undoDelete] can restore it, and navigation waits for the screen's snackbar
     * to resolve ([undoDelete] or [confirmDelete]). Balances already drop via the reactive read path.
     */
    fun delete() {
        val loaded = _state.value.loaded ?: return
        if (_state.value.undoableDelete != null) return // already deleted with an undo pending
        viewModelScope.launch {
            _state.update { it.copy(isSubmitting = true, error = null) }
            try {
                deleteTransaction(loaded.id)
                _state.update { it.copy(isSubmitting = false, undoableDelete = loaded) }
            } catch (e: WalletException) {
                _state.update { it.copy(isSubmitting = false, error = e.message) }
            }
        }
    }

    /** Re-hydrate every form field from a stored row, resetting transient/one-shot state. */
    private fun EditTransactionUiState.populatedFrom(tx: Transaction): EditTransactionUiState = when (tx) {
        is Transaction.Income -> copyLoaded(
            tx, TransactionType.INCOME, tx.accountId, tx.categoryId, tx.subCategoryId, null, tx.amount,
        )
        is Transaction.Expense -> copyLoaded(
            tx, TransactionType.EXPENSE, tx.accountId, tx.categoryId, tx.subCategoryId, null, tx.amount,
        )
        is Transaction.Transfer -> copyLoaded(
            tx, TransactionType.TRANSFER, tx.sourceAccountId, null, null, tx.destAccountId, tx.sourceAmount,
        )
    }

    private fun EditTransactionUiState.copyLoaded(
        tx: Transaction,
        type: TransactionType,
        accountId: Long,
        categoryId: Long?,
        subCategoryId: Long?,
        destAccountId: Long?,
        amount: Money,
    ) = copy(
        loaded = tx,
        type = type,
        selectedAccountId = accountId,
        selectedCategoryId = categoryId,
        // Seeded here for the same reason it is written on save: the form now owns this field, so
        // leaving it unseeded would make editing anything at all clear the stored sub-category.
        selectedSubCategoryId = subCategoryId,
        destAccountId = destAccountId,
        amountInput = moneyParser.toInputString(amount.amount),
        date = tx.date,
        note = tx.note ?: "",
        isSubmitting = false,
        error = null,
        saved = false,
        deleted = false,
        undoableDelete = null,
    )

    /**
     * Fold the edited form back onto the loaded row, preserving its id and the fields the form never
     * exposes. Income/expense may move account/category/amount/date/note; a transfer keeps its locked
     * accounts and rate, changing only the source amount (the domain re-derives the destination leg).
     */
    private fun buildEdited(snapshot: EditTransactionUiState, loaded: Transaction): Transaction {
        val note = snapshot.note.trim().ifEmpty { null }
        val date = snapshot.date ?: loaded.date
        return when (loaded) {
            is Transaction.Income -> {
                val account = requireAccount(snapshot)
                loaded.copy(
                    accountId = account.id,
                    amount = moneyParser.parseAmount(snapshot.amountInput, account.currencyCode),
                    categoryId = requireCategory(snapshot),
                    subCategoryId = snapshot.selectedSubCategoryId,
                    date = date,
                    note = note,
                )
            }
            is Transaction.Expense -> {
                val account = requireAccount(snapshot)
                loaded.copy(
                    accountId = account.id,
                    amount = moneyParser.parseAmount(snapshot.amountInput, account.currencyCode),
                    categoryId = requireCategory(snapshot),
                    subCategoryId = snapshot.selectedSubCategoryId,
                    date = date,
                    note = note,
                )
            }
            is Transaction.Transfer -> loaded.copy(
                // Currency comes from the locked source leg, so the domain's pair-guard always passes.
                sourceAmount = moneyParser.parseAmount(snapshot.amountInput, loaded.sourceAmount.currencyCode),
                date = date,
                note = note,
            )
        }
    }

    private fun requireAccount(snapshot: EditTransactionUiState): Account =
        snapshot.accounts.firstOrNull { it.id == snapshot.selectedAccountId }
            ?: throw IllegalArgumentException("Choose an account")

    private fun requireCategory(snapshot: EditTransactionUiState): Long =
        snapshot.selectedCategoryId ?: throw IllegalArgumentException("Choose a category")
}
