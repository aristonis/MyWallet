package org.aristonis.mywallet.domain.usecase

import org.aristonis.mywallet.domain.error.WalletException
import org.aristonis.mywallet.domain.ledger.EntrySaveContext
import org.aristonis.mywallet.domain.ledger.Leg
import org.aristonis.mywallet.domain.ledger.LegRole
import org.aristonis.mywallet.domain.ledger.LegTarget
import org.aristonis.mywallet.domain.ledger.TrackingRule
import org.aristonis.mywallet.domain.ledger.recipe
import org.aristonis.mywallet.domain.ledger.toParts
import org.aristonis.mywallet.domain.model.CategoryKind
import org.aristonis.mywallet.domain.model.DebtRef
import org.aristonis.mywallet.domain.model.FxSnapshot
import org.aristonis.mywallet.domain.model.Transaction
import org.aristonis.mywallet.domain.port.AccountRepository
import org.aristonis.mywallet.domain.port.CategoryRepository
import org.aristonis.mywallet.domain.port.DebtRepository
import org.aristonis.mywallet.domain.port.DeductionItemRepository
import org.aristonis.mywallet.domain.port.EntryRepository
import org.aristonis.mywallet.domain.port.FxRepository
import org.aristonis.mywallet.domain.port.LedgerQueries
import org.aristonis.mywallet.domain.port.SettingsRepository
import org.aristonis.mywallet.domain.port.TransactionRunner
import org.aristonis.mywallet.domain.service.DebtBalance
import org.aristonis.mywallet.domain.service.DebtEntryGuard
import org.aristonis.mywallet.domain.service.DebtGuard
import org.aristonis.mywallet.domain.service.RateBook
import org.aristonis.mywallet.domain.service.RoleCategories
import java.math.BigDecimal
import java.time.LocalDate

/** What a save stored: the entry's id (new or kept) and the date it was filed under. */
data class SavedEntry(val id: Long, val date: LocalDate)

/**
 * Saves a new entry (id 0) or rewrites an existing one, of any kind, as one database transaction.
 *
 * References and rates are checked inside the runner. Each touched debt is compared before and after
 * the write, including a debt an edit moved away from. An existing negative balance may stay unchanged
 * or improve, but cannot worsen. Refusal rolls back the entry, newly resolved debts,
 * role fallback categories, details and typed rates together.
 *
 * The save decides every rate; any rate on the entry it is given is ignored. Rates come from
 * [RateBook]: base 1, then typed, then saved, else none. A new entry in a currency with no rate is
 * stored with a null rate, and that null is what later reports it as missing. On an edit, a leg keeps
 * the rate stored on it only when its currency is unchanged and no rate was typed for it, so moving a
 * USD expense at 13000 onto a SYP account cannot carry 13000 onto the SYP amount.
 *
 * A new transfer's destination and rate are worked out from the source amount and the rates; an
 * edited transfer keeps its currency pair and is re-derived at its stored rate.
 *
 * With "Save rates I enter" on, the typed rates become the saved rates after the entry is written,
 * through [SetExchangeRate], which also fills every category leg in that currency that still has no
 * rate. The fill lives there alone, so a rate saved here and one saved on the rates screen behave
 * the same way.
 */
class SaveEntry(
    private val runner: TransactionRunner,
    private val accounts: AccountRepository,
    private val categories: CategoryRepository,
    private val entries: EntryRepository,
    private val fx: FxRepository,
    private val settings: SettingsRepository,
    private val setExchangeRate: SetExchangeRate,
    private val debts: DebtRepository,
    ledger: LedgerQueries,
    private val roleCategories: RoleCategories,
    private val deductions: DeductionItemRepository,
) {
    private val debtGuard = DebtEntryGuard(debts, ledger)

    suspend operator fun invoke(
        entry: Transaction,
        typedRates: Map<String, BigDecimal>,
        debt: DebtRef? = null,
        dueDate: LocalDate? = null,
    ): SavedEntry =
        runner.inTransaction {
            val old = if (entry.id == 0L) null else {
                entries.findById(entry.id) ?: throw WalletException.TransactionNotFound(entry.id)
            }
            val snapshot = fx.currentFx()
            requireSaveValues(entry, snapshot, deductions, debt)
            val categorized = withDeductionCategoryParents(entry)
            requireIncomeDebtReferences(categorized)
            val book = rateBookFor(snapshot, typedRates)
            val priced = categorized.recipe().prepareForSave(
                categorized, EntrySaveContext(old, snapshot, book, accounts, roleCategories, debts, debt),
            )
            val recipe = priced.recipe()
            val parts = recipe.toLegs(priced)
            require(debt == null || parts.legs.any { it.target is LegTarget.Debt }) {
                "a debt reference needs an entry with a debt leg"
            }
            parts.legs.firstOrNull { it.amount.currencyCode !in snapshot.decimalPlaces }
                ?.let { throw WalletException.CurrencyNotFound(it.amount.currencyCode) }
            requireReferences(parts.legs, recipe.trackingRules(priced))

            val oldLegs = old?.toParts()?.legs.orEmpty()
            val touched = DebtGuard.touchedDebtIds(oldLegs, parts.legs)
            // Reusing a settled person starts fresh; editing their existing loan keeps its details.
            val reopenCandidates = DebtGuard.touchedDebtIds(emptyList(), parts.legs) -
                DebtGuard.touchedDebtIds(oldLegs, emptyList())
            val before = debtGuard.remaining(touched)
            // A new entry has no old legs, so every category leg is rated afresh.
            val stamped = recipe.fromLegs(parts.copy(legs = book.restamp(parts.legs, oldLegs)))
            val id = if (old == null) entries.insert(stamped) else stamped.id.also { entries.replace(stamped) }

            val after = debtGuard.check(touched, before)
            clearReopenedDetails(reopenCandidates, before, after)
            applyPaidLaterDueDate(stamped, dueDate)

            if (settings.get().saveTransactionRates) keepTypedRates(typedRates, snapshot.baseCurrencyCode)
            SavedEntry(id = id, date = stamped.date)
        }

    /** A supplied paid-later date follows reopening, which clears the previous obligation's details. */
    private suspend fun applyPaidLaterDueDate(entry: Transaction, dueDate: LocalDate?) {
        if (dueDate == null || entry !is Transaction.Expense) return
        val debtId = entry.owedDebtId ?: return
        val current = debtGuard.requireDebt(debtId)
        debts.updateDetails(debtId, dueDate, current.note)
    }

    /** Older deduction drafts carry the selected child id in the category field. */
    private suspend fun withDeductionCategoryParents(entry: Transaction): Transaction {
        if (entry !is Transaction.Income) return entry
        return entry.copy(deductions = entry.deductions.map { line ->
            if (line.subCategoryId != null) return@map line
            val category = categories.findById(line.categoryId)
                ?: throw WalletException.CategoryNotFound(line.categoryId)
            val parentId = category.parentId ?: return@map line
            val parent = categories.findById(parentId) ?: throw WalletException.CategoryNotFound(parentId)
            if (parent.kind != CategoryKind.EXPENSE ||
                category.kind != CategoryKind.EXPENSE
            ) throw WalletException.CategoryWrongKind(category.id)
            line.copy(categoryId = parentId, subCategoryId = category.id)
        })
    }

    /**
     * A debt id an edit carries must still exist. Whether it still fits the line (payee, side, currency)
     * is decided while preparing the save, which re-resolves a debt that no longer fits.
     */
    private suspend fun requireIncomeDebtReferences(entry: Transaction) {
        if (entry !is Transaction.Income) return
        entry.deductions.forEach { line ->
            // Preparation removes zero obligations; their old legs still enforce repayment limits.
            if (line.amount.isZero) return@forEach
            line.debtId?.let { id -> debtGuard.requireDebt(id) }
        }
    }

    private suspend fun clearReopenedDetails(
        ids: Set<Long>,
        before: Map<Long, DebtBalance>,
        after: Map<Long, DebtBalance>,
    ) {
        ids.forEach { id ->
            if (before[id]?.remaining?.isZero != false && after[id]?.remaining?.isPositive == true) {
                debts.updateDetails(id, dueDate = null, note = null)
            }
        }
    }

    /** A typed rate must be for a currency the app tracks; [RateBook] refuses one of zero or less. */
    private fun rateBookFor(snapshot: FxSnapshot, typedRates: Map<String, BigDecimal>): RateBook {
        typedRates.keys.firstOrNull { it !in snapshot.decimalPlaces }
            ?.let { throw WalletException.CurrencyNotFound(it) }
        return RateBook(snapshot, typedRates)
    }

    private suspend fun requireReferences(legs: List<Leg>, rules: Map<LegRole, TrackingRule>) {
        legs.forEach { leg ->
            when (val target = leg.target) {
                is LegTarget.Account -> requireLiveAccountHolds(target.id, leg.amount.currencyCode)
                is LegTarget.Category -> {
                    val rule = checkNotNull(rules[leg.role]) { "no tracking rule for the ${leg.role} leg" }
                    requireCategoryOfKind(target, rule)
                }
                is LegTarget.Debt -> debtGuard.requireReference(target, leg.amount.currencyCode)
            }
        }
    }

    private suspend fun requireLiveAccountHolds(accountId: Long, currencyCode: String) {
        val account = accounts.findById(accountId) ?: throw WalletException.AccountNotFound(accountId)
        if (account.archived) throw WalletException.AccountArchived(accountId)
        if (currencyCode != account.currencyCode) {
            throw WalletException.CurrencyMismatch(currencyCode, account.currencyCode)
        }
    }

    /** Income legs need an income category; expense legs and refunds of them need an expense one. */
    private suspend fun requireCategoryOfKind(target: LegTarget.Category, rule: TrackingRule) {
        categories.requireTransactionCategories(target.id, target.subId)
        val category = categories.findById(target.id) ?: throw WalletException.CategoryNotFound(target.id)
        if (category.kind != rule.categoryKind()) throw WalletException.CategoryWrongKind(target.id)
    }

    /**
     * Saves each typed rate, which also gives it to the legs still waiting for one. The base is
     * skipped: it is always 1, and a stored row for it would be read as a real rate after the next
     * base change.
     */
    private suspend fun keepTypedRates(typedRates: Map<String, BigDecimal>, baseCurrencyCode: String) {
        typedRates.filterKeys { it != baseCurrencyCode }.forEach { (code, rate) -> setExchangeRate(code, rate) }
    }
}
