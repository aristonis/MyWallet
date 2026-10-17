package org.aristonis.mywallet.domain.usecase

import org.aristonis.mywallet.domain.error.WalletException
import org.aristonis.mywallet.domain.ledger.LegTarget
import org.aristonis.mywallet.domain.ledger.recipe.ExpenseRecipe
import org.aristonis.mywallet.domain.ledger.recipe.IncomeRecipe
import org.aristonis.mywallet.domain.ledger.toParts
import org.aristonis.mywallet.domain.model.DebtDeletionPreview
import org.aristonis.mywallet.domain.model.Money
import org.aristonis.mywallet.domain.model.Transaction
import org.aristonis.mywallet.domain.port.DebtRepository
import org.aristonis.mywallet.domain.port.EntryRepository
import org.aristonis.mywallet.domain.port.LedgerQueries
import org.aristonis.mywallet.domain.port.TransactionRunner
import org.aristonis.mywallet.domain.service.DebtRemaining

/** Reads a deletion preview from one transaction without changing entries or debt details. */
class PreviewDebtDeletion(
    private val runner: TransactionRunner,
    private val debts: DebtRepository,
    private val entries: EntryRepository,
    private val ledger: LedgerQueries,
) {
    suspend operator fun invoke(debtId: Long): DebtDeletionPreview = runner.inTransaction {
        val debt = debts.findById(debtId) ?: throw WalletException.DebtNotFound(debtId)
        val ownEntries = entries.deletableDebtEntries(debtId)
        val removedIds = ownEntries.map { it.id }.toSet()
        val retainedIds = entries.entryIdsOnDebt(debtId, emptySet()).filterNot { it in removedIds }
        val remaining = DebtRemaining.byDebt(ledger.debtLegs(setOf(debtId)))[debtId]?.remaining
            ?: Money.zero(debt.currencyCode)
        val removedAmount = ownEntries.flatMap { it.toParts().legs }
            .filter { (it.target as? LegTarget.Debt)?.id == debtId }
            .fold(Money.zero(debt.currencyCode)) { sum, leg -> sum + leg.amount }
        DebtDeletionPreview(debt, ownEntries, retainedIds, remaining - removedAmount)
    }
}

/** Mixed-purpose entries are excluded in storage before decoding; callers hold the runner. */
internal suspend fun EntryRepository.deletableDebtEntries(debtId: Long): List<Transaction> =
    entryIdsOnDebt(debtId, setOf(IncomeRecipe.kind, ExpenseRecipe.kind)).map { id ->
        findById(id) ?: throw WalletException.TransactionNotFound(id)
    }
