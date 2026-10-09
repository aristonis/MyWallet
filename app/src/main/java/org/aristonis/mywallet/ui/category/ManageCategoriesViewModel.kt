package org.aristonis.mywallet.ui.category

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.aristonis.mywallet.di.ErrorReporter
import org.aristonis.mywallet.domain.error.WalletException
import org.aristonis.mywallet.ui.message.UiMessage
import org.aristonis.mywallet.ui.message.toUiMessage
import org.aristonis.mywallet.domain.model.Category
import org.aristonis.mywallet.domain.model.CategoryKind
import org.aristonis.mywallet.domain.model.Transaction
import org.aristonis.mywallet.domain.port.CategoryRepository
import org.aristonis.mywallet.domain.port.TransactionRepository
import org.aristonis.mywallet.domain.usecase.CreateCategory
import org.aristonis.mywallet.domain.usecase.DeleteCategory
import org.aristonis.mywallet.domain.usecase.RenameCategory
import org.aristonis.mywallet.ui.load.ReadAttempts
import javax.inject.Inject

/**
 * One top-level category and the sub-categories filed under it.
 *
 * [affectedTransactions] is what the delete confirmation quotes, so it counts the children's
 * transactions too — a user shown "1 transaction" who then loses three has been misled at the one
 * moment they were asked to decide. [systemKey] is non-null for an app-owned bucket, whose stored
 * name is a placeholder: the screen resolves the label from the key, so the bucket reads correctly
 * in any language without anything having to rewrite the row.
 */
data class ManageCategoryRow(
    val category: Category,
    val children: List<Category> = emptyList(),
    val affectedTransactions: Int = 0,
    val systemKey: String? = category.systemKey,
    val isProtected: Boolean = category.isSystem,
)

/** Immutable snapshot the Manage Categories screen renders from. */
data class ManageCategoriesUiState(
    val income: List<ManageCategoryRow> = emptyList(),
    val expense: List<ManageCategoryRow> = emptyList(),
    val isLoading: Boolean = true,
    val error: UiMessage? = null,
    /** What the last delete did; one-shot, cleared by [ManageCategoriesViewModel.acknowledge]. */
    val lastDelete: DeleteOutcome? = null,
    /**
     * The categories or the entries behind their counts could not be read. The screen says so
     * instead of listing nothing, which would read as "no categories" and invite duplicates.
     */
    val loadFailed: Boolean = false,
)

/**
 * What a completed delete did to the transactions behind it.
 *
 * The two cases are genuinely different and cannot share a sentence: deleting a sub-category clears
 * the finer label and leaves the spend under the same parent, while deleting a parent moves it into
 * the fallback bucket. Reporting the second wording for the first names the wrong destination.
 */
data class DeleteOutcome(val affected: Int, val wasSubCategory: Boolean)

/** The categories a transaction points at. A transfer is never categorized, so it names none. */
private val Transaction.referencedCategoryIds: List<Long>
    get() = when (this) {
        is Transaction.Income -> listOfNotNull(categoryId, subCategoryId)
        is Transaction.Expense -> listOfNotNull(categoryId, subCategoryId)
        is Transaction.Transfer -> emptyList()
    }

/** Where one attempt to read the categories and the entries behind their counts stands. */
private sealed interface CategoriesRead {
    /** A retry is reading again and nothing has answered yet. */
    data object Reading : CategoriesRead

    data object Failed : CategoriesRead

    data class Read(val categories: List<Category>, val transactions: List<Transaction>) : CategoriesRead
}

/**
 * Manage the category tree: list both kinds with their sub-categories, and add, rename or delete.
 *
 * Deleting never destroys history, so the screen's job is to say how many transactions a delete will
 * touch before the user agrees, and what became of them afterwards. Those are two different answers
 * depending on whether a parent or a sub-category went.
 *
 * A read that throws (a stored row that cannot be decoded) becomes a failed state, reported to
 * [ErrorReporter], instead of crashing the app; [retry] reads again.
 */
@HiltViewModel
class ManageCategoriesViewModel @Inject constructor(
    categories: CategoryRepository,
    transactions: TransactionRepository,
    private val createCategory: CreateCategory,
    private val renameCategory: RenameCategory,
    private val deleteCategory: DeleteCategory,
    private val errors: ErrorReporter,
) : ViewModel() {

    private val _state = MutableStateFlow(ManageCategoriesUiState())
    val state: StateFlow<ManageCategoriesUiState> = _state.asStateFlow()

    /** The latest categories and transactions, kept for the per-row counts the screen asks for. */
    private var latestCategories: List<Category> = emptyList()
    private var latestTransactions: List<Transaction> = emptyList()

    /** Starts the reads over on [retry]. */
    private val attempts = ReadAttempts()

    init {
        // The catch sits inside the attempt, so it ends only the attempt that failed and a later
        // [retry] can still read.
        attempts.reading(loading = { CategoriesRead.Reading }) {
            combine(categories.observeAll(), transactions.observeAll()) { categoryList, transactionList ->
                CategoriesRead.Read(categoryList, transactionList)
            }
                .catch<CategoriesRead> { error ->
                    if (error is CancellationException) throw error
                    errors.report("Manage categories could not read the categories", error)
                    emit(CategoriesRead.Failed)
                }
        }
            .onEach(::show)
            .launchIn(viewModelScope)
    }

    /** Reads the categories again after a failed read, showing them as loading until it answers. */
    fun retry() = attempts.retry()

    /** How many transactions deleting [categoryId] would re-file, including any under its children. */
    fun affectedBy(categoryId: Long): Int {
        val doomed = setOf(categoryId) + latestCategories.filter { it.parentId == categoryId }.map { it.id }
        return latestTransactions.count { tx -> tx.referencedCategoryIds.any { it in doomed } }
    }

    fun create(name: String, kind: CategoryKind, parentId: Long?) = perform {
        createCategory(name, kind, parentId)
        null
    }

    fun rename(id: Long, name: String) = perform {
        renameCategory(id, name)
        null
    }

    fun delete(id: Long) = perform {
        // Read the shape before the row is gone; afterwards there is nothing left to ask.
        val wasSubCategory = latestCategories.firstOrNull { it.id == id }?.isSubCategory == true
        DeleteOutcome(affected = deleteCategory(id), wasSubCategory = wasSubCategory)
    }

    /** Clears the one-shot outcome so returning to the screen does not replay a finished delete. */
    fun acknowledge() = _state.update { it.copy(lastDelete = null, error = null) }

    private fun show(read: CategoriesRead) = when (read) {
        CategoriesRead.Reading -> showReading()
        CategoriesRead.Failed -> showLoadFailed()
        is CategoriesRead.Read -> showCategories(read.categories, read.transactions)
    }

    /** A retry is reading: forget the old rows so a delete preview never counts against them. */
    private fun showReading() {
        latestCategories = emptyList()
        latestTransactions = emptyList()
        _state.update { it.copy(isLoading = true, loadFailed = false) }
    }

    private fun showCategories(categoryList: List<Category>, transactionList: List<Transaction>) {
        latestCategories = categoryList
        latestTransactions = transactionList
        _state.update {
            it.copy(
                income = rowsOf(CategoryKind.INCOME, categoryList, transactionList),
                expense = rowsOf(CategoryKind.EXPENSE, categoryList, transactionList),
                isLoading = false,
                loadFailed = false,
            )
        }
    }

    /**
     * Drops what was shown before the failure, so no delete is offered or counted against rows that
     * may no longer be true.
     */
    private fun showLoadFailed() {
        latestCategories = emptyList()
        latestTransactions = emptyList()
        _state.update {
            it.copy(income = emptyList(), expense = emptyList(), isLoading = false, loadFailed = true)
        }
    }

    private fun rowsOf(
        kind: CategoryKind,
        all: List<Category>,
        transactions: List<Transaction>,
    ): List<ManageCategoryRow> =
        all.filter { it.kind == kind && !it.isSubCategory }
            .sortedBy { it.name }
            .map { parent ->
                val children = all.filter { it.parentId == parent.id }.sortedBy { it.name }
                val doomed = setOf(parent.id) + children.map { it.id }
                ManageCategoryRow(
                    category = parent,
                    children = children,
                    affectedTransactions = transactions.count { tx ->
                        tx.referencedCategoryIds.any { it in doomed }
                    },
                )
            }

    /**
     * Runs a category action, reporting a domain refusal as text the screen can show and carrying
     * back what a delete did, when the action was one. Cancellation is rethrown so a view-model torn
     * down mid-write is not mistaken for a failed edit.
     */
    private fun perform(action: suspend () -> DeleteOutcome?) {
        viewModelScope.launch {
            try {
                val outcome = action()
                _state.update { it.copy(error = null, lastDelete = outcome) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: WalletException) {
                _state.update { it.copy(error = e.toUiMessage()) }
            } catch (e: IllegalArgumentException) {
                _state.update { it.copy(error = e.toUiMessage()) }
            }
        }
    }
}
