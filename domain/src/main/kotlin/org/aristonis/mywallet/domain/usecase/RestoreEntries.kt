package org.aristonis.mywallet.domain.usecase

import org.aristonis.mywallet.domain.error.WalletException
import org.aristonis.mywallet.domain.ledger.Leg
import org.aristonis.mywallet.domain.ledger.LegRole
import org.aristonis.mywallet.domain.ledger.LegTarget
import org.aristonis.mywallet.domain.ledger.TrackingRule
import org.aristonis.mywallet.domain.ledger.recipe
import org.aristonis.mywallet.domain.ledger.toParts
import org.aristonis.mywallet.domain.model.Transaction
import org.aristonis.mywallet.domain.model.UndoBundle
import org.aristonis.mywallet.domain.port.AccountRepository
import org.aristonis.mywallet.domain.port.CategoryRepository
import org.aristonis.mywallet.domain.port.DebtRepository
import org.aristonis.mywallet.domain.port.DeductionItemRepository
import org.aristonis.mywallet.domain.port.EntryRepository
import org.aristonis.mywallet.domain.port.LedgerQueries
import org.aristonis.mywallet.domain.port.TransactionRunner
import org.aristonis.mywallet.domain.service.DebtEntryGuard
import org.aristonis.mywallet.domain.service.DebtGuard

/**
 * Puts back what [DeleteEntry] removed, each entry at its original id and with the rates it was
 * recorded with, as one unit: every entry returns or none does.
 *
 * Accounts, deduction items and debt references are checked before anything is written. A category
 * deleted after the entry was is treated as deleting it would have treated a live entry: a leg on a
 * missing category moves to the Uncategorized bucket of the kind its role counts on, and a leg whose
 * sub-category is missing keeps its live parent and loses only the finer label. A deduction line
 * follows its leg, since its category is the leg's. Amounts, accounts, debts and the rest of each line
 * come back exactly. Categories that still exist must keep the required tree structure.
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
    private val deductions: DeductionItemRepository,
) {
    private val debtGuard = DebtEntryGuard(debts, ledger)

    suspend operator fun invoke(bundle: UndoBundle) {
        // An id of 0 would insert as a new entry: a duplicate, not an undo.
        require(bundle.entries.all { it.id > 0 }) { "an undo bundle holds only stored entries" }
        if (bundle.entries.isEmpty() && bundle.debts.isEmpty()) return
        runner.inTransaction {
            bundle.entries.filterIsInstance<Transaction.Income>().forEach { income ->
                requireDeductionItems(income, deductions)
            }
            val recorded = bundle.entries.flatMap { it.toParts().legs }
            requireAccountReferences(recorded)
            debtGuard.requireReferences(recorded)
            val restored = bundle.entries.map { withLiveCategories(it) }
            val legs = restored.flatMap { it.toParts().legs }
            requireCategoryReferences(legs)
            val currentDebts = bundle.debts.associate { it.id to debtGuard.requireDebt(it.id) }
            val touched = DebtGuard.touchedDebtIds(emptyList(), legs) + currentDebts.keys
            val before = debtGuard.remaining(touched)
            restored.forEach { entries.insert(it) }
            bundle.debts.forEach { snapshot ->
                val current = currentDebts.getValue(snapshot.id)
                debts.updateDetails(snapshot.id, current.dueDate ?: snapshot.dueDate, current.note ?: snapshot.note)
            }
            debtGuard.check(touched, before, bundle.debtRemainingAtDelete)
        }
    }

    private suspend fun requireAccountReferences(legs: List<Leg>) {
        val ids = legs.mapNotNull { (it.target as? LegTarget.Account)?.id }.distinct()
        val currencies = ids.associateWith { id ->
            (accounts.findById(id) ?: throw WalletException.AccountNotFound(id)).currencyCode
        }
        legs.forEach { leg ->
            val account = leg.target as? LegTarget.Account ?: return@forEach
            val currency = currencies.getValue(account.id)
            if (leg.amount.currencyCode != currency) {
                throw WalletException.CurrencyMismatch(leg.amount.currencyCode, currency)
            }
        }
    }

    private suspend fun requireCategoryReferences(legs: List<Leg>) {
        legs.forEach { leg ->
            val category = leg.target as? LegTarget.Category ?: return@forEach
            categories.requireTransactionCategories(category.id, category.subId)
        }
    }

    /** [entry] with every category leg on a category that still exists; unchanged when all do. */
    private suspend fun withLiveCategories(entry: Transaction): Transaction {
        val recipe = entry.recipe()
        val rules = recipe.trackingRules(entry)
        val parts = entry.toParts()
        val legs = parts.legs.map { leg ->
            val target = leg.target as? LegTarget.Category ?: return@map leg
            val rule = checkNotNull(rules[leg.role]) { "no tracking rule for the ${leg.role} leg" }
            leg.copy(target = liveTarget(target, rule))
        }
        if (legs == parts.legs) return entry
        val lines = parts.deductionLines.map { line ->
            val leg = legs.single { it.role == LegRole.DEDUCTION && it.position == line.position }
            val target = leg.target as LegTarget.Category
            line.copy(categoryId = target.id, subCategoryId = target.subId)
        }
        return recipe.fromLegs(parts.copy(legs = legs, deductionLines = lines))
    }

    /** Where a live entry on [target] would sit now had its category been deleted while it existed. */
    private suspend fun liveTarget(target: LegTarget.Category, rule: TrackingRule): LegTarget.Category {
        if (categories.findById(target.id) == null) {
            return LegTarget.Category(categories.bucketFor(rule.categoryKind()), null)
        }
        val subId = target.subId ?: return target
        return if (categories.findById(subId) == null) target.copy(subId = null) else target
    }
}
