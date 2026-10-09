package org.aristonis.mywallet.domain.usecase

import org.aristonis.mywallet.domain.error.WalletException
import org.aristonis.mywallet.domain.ledger.Leg
import org.aristonis.mywallet.domain.ledger.LegRole
import org.aristonis.mywallet.domain.ledger.LegTarget
import org.aristonis.mywallet.domain.ledger.TrackingRule
import org.aristonis.mywallet.domain.ledger.recipe
import org.aristonis.mywallet.domain.ledger.toParts
import org.aristonis.mywallet.domain.model.CategoryKind
import org.aristonis.mywallet.domain.model.FxSnapshot
import org.aristonis.mywallet.domain.model.Transaction
import org.aristonis.mywallet.domain.port.AccountRepository
import org.aristonis.mywallet.domain.port.CategoryRepository
import org.aristonis.mywallet.domain.port.EntryRepository
import org.aristonis.mywallet.domain.port.FxRepository
import org.aristonis.mywallet.domain.port.LedgerQueries
import org.aristonis.mywallet.domain.port.SettingsRepository
import org.aristonis.mywallet.domain.port.TransactionRunner
import org.aristonis.mywallet.domain.service.RateBook
import java.math.BigDecimal
import java.time.LocalDate

/** What a save stored: the entry's id (new or kept) and the date it was filed under. */
data class SavedEntry(val id: Long, val date: LocalDate)

/**
 * Saves a new entry (id 0) or rewrites an existing one, of any kind, as one database transaction.
 *
 * Everything is checked before anything is written: each account leg's account exists, is live and
 * holds the leg's currency; each category leg's category is a valid pair and of the kind the leg
 * counts as; each typed rate is above zero and for a known currency. A refused save therefore leaves
 * no entry and no typed rate behind.
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
 * and fill every category leg in that currency that still has no rate.
 */
class SaveEntry(
    private val runner: TransactionRunner,
    private val accounts: AccountRepository,
    private val categories: CategoryRepository,
    private val entries: EntryRepository,
    private val ledger: LedgerQueries,
    private val fx: FxRepository,
    private val settings: SettingsRepository,
    private val setExchangeRate: SetExchangeRate,
) {
    suspend operator fun invoke(entry: Transaction, typedRates: Map<String, BigDecimal>): SavedEntry =
        runner.inTransaction {
            val old = if (entry.id == 0L) null else {
                entries.findById(entry.id) ?: throw WalletException.TransactionNotFound(entry.id)
            }
            val snapshot = fx.currentFx()
            val book = rateBookFor(snapshot, typedRates)
            val priced = if (entry is Transaction.Transfer) priceTransfer(entry, old, snapshot, book) else entry
            val recipe = priced.recipe()
            val parts = recipe.toLegs(priced)
            requireReferences(parts.legs, recipe.trackingRules(priced))

            // A new entry has no old legs, so every category leg is rated afresh.
            val oldLegs = old?.toParts()?.legs.orEmpty()
            val stamped = recipe.fromLegs(parts.copy(legs = book.restamp(parts.legs, oldLegs)))
            val id = if (old == null) entries.insert(stamped) else stamped.id.also { entries.replace(stamped) }

            if (settings.get().saveTransactionRates) keepTypedRates(typedRates, snapshot.baseCurrencyCode)
            SavedEntry(id = id, date = stamped.date)
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
                // No kind writes a debt leg yet; one arriving here has nothing to check it against.
                is LegTarget.Debt -> error("debt legs are not supported yet")
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
        val expected = when (rule) {
            TrackingRule.INCOME -> CategoryKind.INCOME
            TrackingRule.EXPENSE, TrackingRule.REDUCES_EXPENSE -> CategoryKind.EXPENSE
            TrackingRule.NOT_COUNTED -> error("a leg that never counts cannot be on a category")
        }
        if (category.kind != expected) throw WalletException.CategoryWrongKind(target.id)
    }

    /** A new transfer is priced from the rates; an edit keeps its pair and its stored rate. */
    private suspend fun priceTransfer(
        transfer: Transaction.Transfer,
        old: Transaction?,
        snapshot: FxSnapshot,
        book: RateBook,
    ): Transaction.Transfer {
        val destCurrency = accounts.findById(transfer.destAccountId)?.currencyCode
            ?: throw WalletException.AccountNotFound(transfer.destAccountId)
        return if (old == null) {
            transfer.pricedAsNew(destCurrency, book)
        } else {
            transfer.pricedAsEditOf(old, destCurrency, snapshot)
        }
    }

    /**
     * Saves each typed rate and gives it to the legs still waiting for one. The base is skipped: it
     * is always 1, and a stored row for it would be read as a real rate after the next base change.
     */
    private suspend fun keepTypedRates(typedRates: Map<String, BigDecimal>, baseCurrencyCode: String) {
        typedRates.filterKeys { it != baseCurrencyCode }.forEach { (code, rate) ->
            setExchangeRate(code, rate)
            ledger.fillMissingRate(code, rate)
        }
    }
}
