package org.aristonis.mywallet.domain.usecase

import org.aristonis.mywallet.domain.error.WalletException
import org.aristonis.mywallet.domain.ledger.LegTarget
import org.aristonis.mywallet.domain.ledger.toParts
import org.aristonis.mywallet.domain.model.UndoBundle
import org.aristonis.mywallet.domain.port.AccountRepository
import org.aristonis.mywallet.domain.port.CategoryRepository
import org.aristonis.mywallet.domain.port.DebtRepository
import org.aristonis.mywallet.domain.port.EntryRepository
import org.aristonis.mywallet.domain.port.LedgerQueries
import org.aristonis.mywallet.domain.port.TransactionRunner
import org.aristonis.mywallet.domain.service.DebtEntryGuard
import org.aristonis.mywallet.domain.service.DebtGuard

/**
 * Puts back what [DeleteEntry] removed, each entry at its original id and with the rates it was
 * recorded with, as one unit: every entry returns or none does.
 *
 * Every account, category and debt reference is re-checked before anything is written. The entries were valid when
 * deleted, but an account or category can be gone by the time undo runs. A blind re-insert would
 * bring back money filed under a category that no longer exists, or fail on the account as a raw
 * database error no screen can name.
 *
 * Entries go back through [EntryRepository.insert] only, never `replace`. If another entry now holds
 * the id, the insert aborts with [WalletException.EntryIdTaken] and the whole restore rolls back,
 * instead of overwriting that entry.
 *
 * Debt snapshots restore only due dates and notes on rows that still exist; undo never creates a
 * person again. Each field is restored only if it is currently null, preserving newer details. Creating
 * or worsening a negative balance aborts the entries and restored details together. A debt that was
 * already below zero when deleted may come back to that remaining, never below it.
 */
class RestoreEntries(
    private val runner: TransactionRunner,
    private val entries: EntryRepository,
    private val categories: CategoryRepository,
    private val accounts: AccountRepository,
    private val debts: DebtRepository,
    ledger: LedgerQueries,
) {
    private val debtGuard = DebtEntryGuard(debts, ledger)

    suspend operator fun invoke(bundle: UndoBundle) {
        // An id of 0 would insert as a new entry: a duplicate, not an undo.
        require(bundle.entries.all { it.id > 0 }) { "an undo bundle holds only stored entries" }
        if (bundle.entries.isEmpty() && bundle.debts.isEmpty()) return
        runner.inTransaction {
            val legs = bundle.entries.flatMap { it.toParts().legs }
            val targets = legs.map { it.target }
            val accountIds = targets.filterIsInstance<LegTarget.Account>().map { it.id }.distinct()
            val accountCurrencies = accountIds.associateWith { id ->
                (accounts.findById(id) ?: throw WalletException.AccountNotFound(id)).currencyCode
            }
            legs.forEach { leg ->
                val target = leg.target as? LegTarget.Account ?: return@forEach
                val currencyCode = accountCurrencies.getValue(target.id)
                if (leg.amount.currencyCode != currencyCode) {
                    throw WalletException.CurrencyMismatch(leg.amount.currencyCode, currencyCode)
                }
            }
            targets.filterIsInstance<LegTarget.Category>()
                .forEach { categories.requireTransactionCategories(it.id, it.subId) }
            debtGuard.requireReferences(legs)
            val currentDebts = bundle.debts.associate { it.id to debtGuard.requireDebt(it.id) }
            val touched = DebtGuard.touchedDebtIds(emptyList(), legs) + currentDebts.keys
            val before = debtGuard.remaining(touched)
            bundle.entries.forEach { entries.insert(it) }
            bundle.debts.forEach { snapshot ->
                val current = currentDebts.getValue(snapshot.id)
                debts.updateDetails(snapshot.id, current.dueDate ?: snapshot.dueDate, current.note ?: snapshot.note)
            }
            debtGuard.check(touched, before, bundle.debtRemainingAtDelete)
        }
    }
}
