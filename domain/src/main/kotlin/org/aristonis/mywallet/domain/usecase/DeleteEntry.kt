package org.aristonis.mywallet.domain.usecase

import org.aristonis.mywallet.domain.error.WalletException
import org.aristonis.mywallet.domain.ledger.toParts
import org.aristonis.mywallet.domain.model.UndoBundle
import org.aristonis.mywallet.domain.port.DebtRepository
import org.aristonis.mywallet.domain.port.EntryRepository
import org.aristonis.mywallet.domain.port.LedgerQueries
import org.aristonis.mywallet.domain.port.TransactionRunner
import org.aristonis.mywallet.domain.service.DebtEntryGuard
import org.aristonis.mywallet.domain.service.DebtGuard

/**
 * Removes an entry with all its legs and hands back what was removed, so [RestoreEntries] can undo
 * it. The entry is read inside the same transaction as the delete, so the bundle holds exactly the
 * row that went, not one read a moment earlier. A negative debt balance may stay unchanged or improve, but cannot worsen.
 */
class DeleteEntry(
    private val runner: TransactionRunner,
    private val entries: EntryRepository,
    debts: DebtRepository,
    ledger: LedgerQueries,
) {
    private val debtGuard = DebtEntryGuard(debts, ledger)

    suspend operator fun invoke(id: Long): UndoBundle = runner.inTransaction {
        val entry = entries.findById(id) ?: throw WalletException.TransactionNotFound(id)
        val touched = DebtGuard.touchedDebtIds(entry.toParts().legs, emptyList())
        val before = debtGuard.remaining(touched)
        entries.delete(id)
        debtGuard.check(touched, before)
        UndoBundle(entries = listOf(entry))
    }
}
