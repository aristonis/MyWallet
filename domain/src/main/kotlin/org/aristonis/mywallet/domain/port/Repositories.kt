package org.aristonis.mywallet.domain.port

import org.aristonis.mywallet.domain.model.Account
import org.aristonis.mywallet.domain.model.Category
import org.aristonis.mywallet.domain.model.CategoryKind
import org.aristonis.mywallet.domain.model.Currency
import org.aristonis.mywallet.domain.model.DateRange
import org.aristonis.mywallet.domain.model.ExchangeRate
import org.aristonis.mywallet.domain.model.FxSnapshot
import org.aristonis.mywallet.domain.model.Settings
import org.aristonis.mywallet.domain.model.Transaction
import kotlinx.coroutines.flow.Flow

/**
 * Ports = the interfaces the domain core depends on. `:data` (Room) implements them; the domain
 * never sees Room. `suspend` = one-shot I/O (must not block the caller's thread); `Flow` = a live
 * stream that re-emits whenever the underlying data changes. Grown as use-cases need them (YAGNI).
 */

interface AccountRepository {
    fun observeAll(): Flow<List<Account>>
    suspend fun findById(id: Long): Account?

    /** Insert (id == 0) or update; returns the account's id. */
    suspend fun upsert(account: Account): Long

    /** Hard-delete an account by id. Callers must guard against deleting one that has transactions. */
    suspend fun delete(id: Long)
}

interface CategoryRepository {
    fun observeAll(): Flow<List<Category>>
    suspend fun findById(id: Long): Category?

    /** Insert (id == 0) or update; returns the category's id. */
    suspend fun upsert(category: Category): Long

    /**
     * Removes [categoryId] — and, when it is a parent, its children — and re-points every
     * transaction that referenced them, returning how many transaction rows changed.
     *
     * The two cases differ and the difference is the whole point. Deleting a sub-category only
     * clears the finer label, leaving `categoryId` alone so the spend stays in its parent. Deleting
     * a parent moves the spend to the [fallbackKey] bucket of [kind] instead, because there is no
     * longer a category to stay in.
     *
     * The adapter runs the lookup, the bucket's get-or-create and the writes as one atomic unit: a
     * half-applied reassignment would leave transactions pointing at rows that no longer exist, and
     * doing the get-or-create out here would strand an orphan bucket when the rest rolled back.
     */
    suspend fun deleteAndReassign(categoryId: Long, kind: CategoryKind, fallbackKey: String): Int
}

interface TransactionRepository {
    fun observeAll(): Flow<List<Transaction>>

    /**
     * Live transactions dated inside the inclusive [range], newest first (date desc, then id desc, the
     * same order as [observeAll]). The filter runs in storage so a month never loads the whole history;
     * [DateRange.ALL_TIME] returns everything.
     */
    fun observeBetween(range: DateRange): Flow<List<Transaction>>

    /** The transaction with [id], or null if none — used to guard an edit against a stale/deleted row. */
    suspend fun findById(id: Long): Transaction?

    /** Persists a new transaction and returns its generated id. */
    suspend fun add(transaction: Transaction): Long

    /** Replaces an existing transaction, matched by id. */
    suspend fun update(transaction: Transaction)

    /** Removes a transaction by id. A transfer is one row, so both legs go together (atomic). */
    suspend fun delete(id: Long)
}

interface CurrencyRepository {
    fun observeAll(): Flow<List<Currency>>
    suspend fun findByCode(code: String): Currency?
}

interface RateRepository {
    fun observeAll(): Flow<List<ExchangeRate>>
    suspend fun findByCode(code: String): ExchangeRate?

    /** Insert the rate for its currency, or replace the existing one (keyed by currency code). */
    suspend fun upsert(rate: ExchangeRate)
}

/**
 * The conversion context as one consistent read. Implemented over a single database transaction, so
 * a snapshot never mixes a new base currency with the rates that belonged to the old one — the
 * failure that produces a wrong total with no error anywhere.
 */
/**
 * Applies a base-currency change as one all-or-nothing write.
 *
 * It takes the CODE, not a whole [Settings]: an aggregate read outside the write and handed back in
 * carries every other field with it, so a theme change landing in between would be silently undone.
 * The adapter re-reads and copies the row inside its own transaction instead.
 *
 * [rates] REPLACES the stored set — a code absent from it is a code with no rate. That is what
 * removes the new base's own row. An upsert loop would leave it behind, invisible while it is the
 * base, until the next rebase read it as real data and inflated every figure in that currency.
 */
interface BaseCurrencyRepository {
    suspend fun rebase(newBaseCurrencyCode: String, rates: List<ExchangeRate>)
}

interface FxRepository {
    fun observeFx(): Flow<FxSnapshot>
}

interface SettingsRepository {
    /** Emits the saved settings; does NOT emit until settings exist (post-onboarding). */
    fun observe(): Flow<Settings>

    /** Emits `null` while no settings exist yet, then the settings — the "is-onboarded?" signal. */
    fun observeOrNull(): Flow<Settings?>

    suspend fun get(): Settings
    suspend fun save(settings: Settings)
}

/**
 * Reads and writes a full-database backup as one opaque serialized string. The domain deliberately
 * stays unaware of the on-disk format (JSON today, in `:data`) — here it is only text in, text out.
 */
interface BackupRepository {
    /** Serializes the entire wallet (all tables) into one portable string. */
    suspend fun exportBackup(): String

    /** Replaces the entire wallet with the contents of [serialized], all-or-nothing. */
    suspend fun restoreBackup(serialized: String)
}
