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

    /**
     * Deletes every entry that belongs only to [debtId] and clears its due date and note, in one transaction.
     * Returns the removed entries and the debt row so the delete can be undone.
     *
     * @throws WalletException.DebtNotFound when [debtId] has no row.
     * @throws WalletException.DebtBelowZero when the remaining entries would leave a debt owing less than nothing.
     */
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
