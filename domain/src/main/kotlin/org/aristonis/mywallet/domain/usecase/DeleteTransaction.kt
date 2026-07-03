package org.aristonis.mywallet.domain.usecase

import org.aristonis.mywallet.domain.port.TransactionRepository

/**
 * Removes a transaction by id. A transfer is stored as a single row, so both legs go together
 * atomically — deleting one never leaves an orphaned leg. Balances recompute for free
 * via the reactive read path, so no manual recalculation is needed here.
 */
class DeleteTransaction(private val transactions: TransactionRepository) {
    suspend operator fun invoke(id: Long) = transactions.delete(id)
}
