package org.aristonis.mywallet.domain.port

import kotlinx.coroutines.flow.Flow
import org.aristonis.mywallet.domain.ledger.LegRole
import org.aristonis.mywallet.domain.model.DateRange
import org.aristonis.mywallet.domain.model.DebtSide
import org.aristonis.mywallet.domain.model.Money
import org.aristonis.mywallet.domain.model.Transaction
import org.aristonis.mywallet.domain.model.TransactionFilter
import java.math.BigDecimal
import java.time.LocalDate

/**
 * Stores entries as a header plus legs, but speaks [Transaction] kinds so no caller ever handles
 * legs. Every read goes through the kind's recipe, so a row it cannot read surfaces as
 * [org.aristonis.mywallet.domain.error.WalletException.UnreadableRow].
 */
interface EntryRepository {

    /** Every entry, newest first (date desc, then id desc). */
    fun observeAll(): Flow<List<Transaction>>

    /** Entries dated inside the inclusive [range], in the same order as [observeAll]. */
    fun observeBetween(range: DateRange): Flow<List<Transaction>>

    /** Complete entries with any leg on [debtId], newest first, filtered in storage. */
    fun observeOnDebt(debtId: Long): Flow<List<Transaction>>

    /**
     * Distinct ids of entries with a leg on [debtId], newest first. [excludingKinds] filters stored
     * headers before any entry is decoded; an empty set returns every matching id. This one-shot
     * read participates in the caller's transaction.
     */
    suspend fun entryIdsOnDebt(debtId: Long, excludingKinds: Set<String>): List<Long>

    /**
     * At most [limit] entries dated inside the inclusive [range], in the order of [observeAll],
     * starting strictly after the entry [after] names (null starts at the newest). The key marks a
     * place in the order, so it still works after its own entry is deleted.
     *
     * Re-emits whenever an entry changes, so a page is always the rows as they are now. A row no
     * recipe can read fails the flow with
     * [org.aristonis.mywallet.domain.error.WalletException.UnreadableRow].
     */
    fun observePage(range: DateRange, after: EntryKey?, limit: Int): Flow<List<Transaction>>

    /**
     * Applies every filter term before the keyset limit. Storage implementations override this
     * overload; the default only supports an unrestricted read and refuses to silently ignore terms.
     */
    fun observePage(
        range: DateRange,
        after: EntryKey?,
        limit: Int,
        filter: TransactionFilter,
    ): Flow<List<Transaction>> {
        require(filter == TransactionFilter()) { "filtered paging is not implemented" }
        return observePage(range, after, limit)
    }

    /** The entry with [id], or null when there is none. */
    suspend fun findById(id: Long): Transaction?

    /**
     * Writes a new entry and returns its id. Id 0 gets a fresh id; an id above 0 re-inserts at that
     * id (undo) with a plain insert, so a taken id aborts instead of overwriting another entry.
     */
    suspend fun insert(entry: Transaction): Long

    /** Rewrites an existing entry and its legs in place (edit only); the id must exist. */
    suspend fun replace(entry: Transaction)

    /** Removes the entry with [id] together with all its legs. */
    suspend fun delete(id: Long)
}

/**
 * Read-only projections over legs for balances, Tracking and guards. Storage filters and projects;
 * the domain sums with BigDecimal, so no total ever passes through floating point.
 */
interface LedgerQueries {

    /**
     * Every account leg (ACCOUNT, SOURCE, DEST), for summing balances per account. An amount that
     * does not parse fails the flow with
     * [org.aristonis.mywallet.domain.error.WalletException.UnreadableRow] naming its entry.
     */
    fun observeAccountLegs(): Flow<List<AccountLeg>>

    /**
     * Every debt leg, for summing what remains on each debt: the leg's signed amount is in the debt's
     * currency and [DebtLeg.side] is the debt row's own. An amount that does not parse, or a side
     * that is not a [DebtSide], fails the flow with
     * [org.aristonis.mywallet.domain.error.WalletException.UnreadableRow] naming its entry.
     */
    fun observeDebtLegs(): Flow<List<DebtLeg>>

    /** Current legs on [debtIds], read once inside a write transaction for its guards. */
    suspend fun debtLegs(debtIds: Set<Long>): List<DebtLeg>

    /**
     * Every category leg of entries dated inside the inclusive [range], for Tracking, in no
     * particular order: Tracking sums them and finds the newest leg it needs from each leg's date
     * and entry id, so the read never pays for a sort.
     */
    fun observeCategoryLegs(range: DateRange): Flow<List<CategoryLeg>>

    /**
     * The first stored entry whose kind is not in [known], or null when every entry has a recipe.
     * Screens that read projections instead of entries use it so an unreadable row is reported,
     * not silently summed or skipped.
     */
    fun observeUnknownKind(known: Set<String>): Flow<UnreadableRowRef?>

    /** Whether any leg is on [accountId]; an account in use cannot be deleted. */
    suspend fun isAccountInUse(accountId: Long): Boolean

    /**
     * The ids of every account [isAccountInUse] answers true for, read again whenever the stored
     * entries or accounts change. A screen listing accounts reads this to know which can be deleted,
     * without loading any entry.
     */
    fun observeAccountsInUse(): Flow<Set<Long>>

    /**
     * For each category id, how many entries have a category leg on it, as main or sub-category.
     * Categories no entry uses are absent.
     */
    fun observeCategoryUsage(): Flow<Map<Long, Int>>

    /**
     * Sets [rate] on every category leg in currency [code] that has no rate stored yet, and returns
     * how many legs changed. Legs that already carry a rate keep it: it is the rate they were
     * recorded with.
     */
    suspend fun fillMissingRate(code: String, rate: BigDecimal): Int

    /** Each distinct (currency, stored rate) pair on category legs, for re-expressing rates on a base change. */
    suspend fun distinctLegRates(): List<LegRate>
}

/** One account leg's signed amount, in the account's own currency. */
data class AccountLeg(val accountId: Long, val amount: Money)

/** One debt leg's signed amount, in the debt's own currency, with the debt's side. */
data class DebtLeg(val debtId: Long, val side: DebtSide, val amount: Money)

/**
 * One category leg with what Tracking needs to place and convert it. Tracking places a leg by the
 * rule its entry kind gives for its role, not by its category's kind: an old income may sit on an
 * expense category, and one entry can hold legs on both sides (an income with deduction expenses).
 * So the leg carries no category kind, and a leg on a category deleted long ago is still counted,
 * under that category id.
 *
 * @property entryKind The entry's stored kind; with [role], it decides the leg's side.
 * @property rateToBase The rate the leg was recorded with; null means none is stored yet.
 */
data class CategoryLeg(
    val entryId: Long,
    val date: LocalDate,
    val categoryId: Long,
    val subCategoryId: Long?,
    val entryKind: String,
    val role: LegRole,
    val amount: Money,
    val rateToBase: BigDecimal?,
)

/**
 * The place of one entry in the entry list, whose order is date descending, then id descending. A
 * page read continues strictly after it.
 */
data class EntryKey(val date: LocalDate, val id: Long)

/** Names a stored entry no recipe can read, so a screen can point at it. */
data class UnreadableRowRef(val id: Long, val kind: String)

/** A currency and one rate stored on its category legs; null when some legs have no rate yet. */
data class LegRate(val currencyCode: String, val rateToBase: BigDecimal?)
