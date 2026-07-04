package org.aristonis.mywallet.domain.port

import org.aristonis.mywallet.domain.model.Account
import org.aristonis.mywallet.domain.model.Category
import org.aristonis.mywallet.domain.model.Currency
import org.aristonis.mywallet.domain.model.ExchangeRate
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
}

interface TransactionRepository {
    fun observeAll(): Flow<List<Transaction>>

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
