package org.aristonis.mywallet.domain.usecase

import org.aristonis.mywallet.domain.error.WalletException
import org.aristonis.mywallet.domain.model.UndoBundle
import org.aristonis.mywallet.domain.port.EntryRepository
import org.aristonis.mywallet.domain.port.TransactionRunner

/**
 * Removes an entry with all its legs and hands back what was removed, so [RestoreEntries] can undo
 * it. The entry is read inside the same transaction as the delete, so the bundle holds exactly the
 * row that went, not one read a moment earlier. Balances recompute through the reactive read path.
 */
class DeleteEntry(
    private val runner: TransactionRunner,
    private val entries: EntryRepository,
) {
    suspend operator fun invoke(id: Long): UndoBundle = runner.inTransaction {
        val entry = entries.findById(id) ?: throw WalletException.TransactionNotFound(id)
        entries.delete(id)
        UndoBundle(entries = listOf(entry))
    }
}
