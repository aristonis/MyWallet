package org.aristonis.mywallet.domain.usecase

import org.aristonis.mywallet.domain.model.Transaction
import org.aristonis.mywallet.domain.port.TransactionRepository

/**
 * Re-adds a previously deleted transaction, undoing a delete. The row is re-inserted through [add] with
 * its original id, which the delete just freed, so it returns at that same id — identity isn't
 * user-visible, and re-adding the whole [Transaction] (already a valid instance) needs no re-validation.
 * A transfer is a single row, so both
 * legs return together. Balances revert for free via the reactive read path.
 */
class RestoreTransaction(private val transactions: TransactionRepository) {
    suspend operator fun invoke(transaction: Transaction): Long = transactions.add(transaction)
}
