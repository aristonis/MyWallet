package org.aristonis.mywallet.domain.usecase

import org.aristonis.mywallet.domain.error.WalletException
import org.aristonis.mywallet.domain.ledger.LegTarget
import org.aristonis.mywallet.domain.ledger.toParts
import org.aristonis.mywallet.domain.model.UndoBundle
import org.aristonis.mywallet.domain.port.AccountRepository
import org.aristonis.mywallet.domain.port.CategoryRepository
import org.aristonis.mywallet.domain.port.EntryRepository
import org.aristonis.mywallet.domain.port.TransactionRunner

/**
 * Puts back what [DeleteEntry] removed, each entry at its original id and with the rates it was
 * recorded with, as one unit: every entry returns or none does.
 *
 * Every account and category is re-checked before anything is written. The entries were valid when
 * deleted, but an account or category can be gone by the time undo runs. A blind re-insert would
 * bring back money filed under a category that no longer exists, or fail on the account as a raw
 * database error no screen can name.
 *
 * Entries go back through [EntryRepository.insert] only, never `replace`. If another entry now holds
 * the id, the insert aborts with [WalletException.EntryIdTaken] and the whole restore rolls back,
 * instead of overwriting that entry.
 */
class RestoreEntries(
    private val runner: TransactionRunner,
    private val entries: EntryRepository,
    private val categories: CategoryRepository,
    private val accounts: AccountRepository,
) {
    suspend operator fun invoke(bundle: UndoBundle) {
        // An id of 0 would insert as a new entry: a duplicate, not an undo.
        require(bundle.entries.all { it.id > 0 }) { "an undo bundle holds only stored entries" }
        if (bundle.entries.isEmpty()) return
        runner.inTransaction {
            val targets = bundle.entries.flatMap { entry -> entry.toParts().legs.map { it.target } }
            targets.filterIsInstance<LegTarget.Account>().map { it.id }.distinct().forEach { id ->
                accounts.findById(id) ?: throw WalletException.AccountNotFound(id)
            }
            targets.filterIsInstance<LegTarget.Category>()
                .forEach { categories.requireTransactionCategories(it.id, it.subId) }
            bundle.entries.forEach { entries.insert(it) }
        }
    }
}
