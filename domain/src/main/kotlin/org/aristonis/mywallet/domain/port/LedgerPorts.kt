package org.aristonis.mywallet.domain.port

import kotlinx.coroutines.flow.Flow
import org.aristonis.mywallet.domain.ledger.LegRole
import org.aristonis.mywallet.domain.model.CategoryKind
import org.aristonis.mywallet.domain.model.DateRange
import org.aristonis.mywallet.domain.model.Money
import org.aristonis.mywallet.domain.model.Transaction
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

    /** Every account leg (ACCOUNT, SOURCE, DEST), for summing balances per account. */
    fun observeAccountLegs(): Flow<List<AccountLeg>>

    /** Every category leg of entries dated inside the inclusive [range], for Tracking. */
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

/**
 * One category leg with what Tracking needs to place and convert it. Tracking places a leg by its
 * category's kind, not the entry's: one entry can hold legs on both sides (an income with deduction
 * expenses).
 *
 * @property entryKind The entry's stored kind.
 * @property categoryKind The kind of the category the leg is on; null when the category row is gone,
 *   which Tracking reads as an unreadable leg.
 * @property rateToBase The rate the leg was recorded with; null means none is stored yet.
 */
data class CategoryLeg(
    val entryId: Long,
    val date: LocalDate,
    val categoryId: Long,
    val subCategoryId: Long?,
    val entryKind: String,
    val categoryKind: CategoryKind?,
    val role: LegRole,
    val amount: Money,
    val rateToBase: BigDecimal?,
)

/** Names a stored entry no recipe can read, so a screen can point at it. */
data class UnreadableRowRef(val id: Long, val kind: String)

/** A currency and one rate stored on its category legs; null when some legs have no rate yet. */
data class LegRate(val currencyCode: String, val rateToBase: BigDecimal?)
