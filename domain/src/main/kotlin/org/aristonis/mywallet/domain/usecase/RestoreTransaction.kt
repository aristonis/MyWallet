package org.aristonis.mywallet.domain.usecase

import org.aristonis.mywallet.domain.model.Transaction
import org.aristonis.mywallet.domain.port.CategoryRepository
import org.aristonis.mywallet.domain.port.TransactionRepository

/**
 * Re-adds a previously deleted transaction, undoing a delete. The row is re-inserted through [add]
 * with its original id, which the delete just freed, so it returns at that same id — identity isn't
 * user-visible. A transfer is a single row, so both legs return together. Balances revert for free
 * via the reactive read path.
 *
 * The categories are re-checked rather than trusted. The row was valid when it was deleted, but the
 * category it names can be gone by the time undo runs, and nothing in the database says so:
 * `categoryId` carries no foreign key, so a blind re-insert would quietly resurrect a transaction
 * pointing at a category that no longer exists. `subCategoryId` does carry one, and that path fails
 * as a raw database error the screen cannot render.
 */
class RestoreTransaction(
    private val transactions: TransactionRepository,
    private val categories: CategoryRepository,
) {
    suspend operator fun invoke(transaction: Transaction): Long {
        when (transaction) {
            is Transaction.Income ->
                categories.requireTransactionCategories(transaction.categoryId, transaction.subCategoryId)
            is Transaction.Expense ->
                categories.requireTransactionCategories(transaction.categoryId, transaction.subCategoryId)
            // Transfers are never categorized, so there is nothing here to go stale.
            is Transaction.Transfer -> Unit
        }
        return transactions.add(transaction)
    }
}
