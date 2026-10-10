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
 * Removes a debt's own entries while retaining its row and income entries. The final batch is
 * guarded once: deleting one loan first can temporarily put a still-present repayment below zero.
 */
class DeleteDebt(
    private val runner: TransactionRunner,
    private val debts: DebtRepository,
    private val entries: EntryRepository,
    ledger: LedgerQueries,
) {
    private val debtGuard = DebtEntryGuard(debts, ledger)

    suspend operator fun invoke(debtId: Long): UndoBundle = runner.inTransaction {
        val debt = debts.findById(debtId) ?: throw WalletException.DebtNotFound(debtId)
        val ownEntries = entries.deletableDebtEntries(debtId)
        val touched = DebtGuard.touchedDebtIds(ownEntries.flatMap { it.toParts().legs }, emptyList()) + debtId
        ownEntries.forEach { entries.delete(it.id) }
        debts.updateDetails(debtId, dueDate = null, note = null)
        debtGuard.check(touched)
        UndoBundle(entries = ownEntries, debts = listOf(debt))
    }
}
