package org.aristonis.mywallet.domain.port

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import org.aristonis.mywallet.domain.model.Account
import org.aristonis.mywallet.domain.model.Category
import org.aristonis.mywallet.domain.model.CategoryKind
import org.aristonis.mywallet.domain.model.Currency
import org.aristonis.mywallet.domain.model.ExchangeRate
import org.aristonis.mywallet.domain.model.FxSnapshot
import org.aristonis.mywallet.domain.model.Settings
import java.io.InputStream
import java.io.OutputStream
import java.math.BigDecimal

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

    /**
     * The id of the app-owned Uncategorized bucket of [kind], created on first need. Idempotent
     * within the enclosing transaction: a second call finds the row the first inserted. It writes,
     * so call it only inside the transaction runner.
     */
    suspend fun bucketFor(kind: CategoryKind): Long
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

    /**
     * Switches the base to [newBaseCurrencyCode], replaces the stored rates with [rates] and rewrites
     * the rates stored on category legs as [legRates] say, all in one transaction.
     *
     * Each leg is rewritten at most once, by the change whose `from` matches the rate it had before
     * the call. Changes are never applied one after another: when one change's `to` equals another's
     * `from`, a leg moved by the first is not moved again by the second.
     */
    suspend fun rebase(newBaseCurrencyCode: String, rates: List<ExchangeRate>, legRates: List<LegRateChange>)
}

/**
 * Every category leg in [currencyCode] whose stored rate equals [from] gets [to]. Rates are compared
 * by value, so `1.10` matches `1.1`; a null [from] matches the legs that have no rate yet. Account
 * and debt legs carry no rate and are never touched.
 */
data class LegRateChange(val currencyCode: String, val from: BigDecimal?, val to: BigDecimal)

interface FxRepository {
    fun observeFx(): Flow<FxSnapshot>

    /**
     * The snapshot as it is now, read once. A write that needs rates reads them through this, because
     * collecting a live stream inside a database transaction can wait on that same transaction.
     */
    suspend fun currentFx(): FxSnapshot = observeFx().first()
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
 * Streams a full-database backup without exposing its file format to the domain. The caller owns
 * and closes both streams; a rejected restore leaves the wallet untouched.
 */
interface BackupRepository {
    /**
     * Reads one consistent wallet snapshot and checks it as restore would, before the caller opens
     * any document. A wallet that restore would refuse throws `WalletException.BackupInvalid` here,
     * so a refused export never truncates the file it was going to replace.
     */
    suspend fun prepareBackup(): PreparedBackup

    /** Validates [input] before replacing the entire wallet in one transaction. */
    suspend fun restoreBackup(input: InputStream)
}

/** A wallet snapshot that has already passed the restore checks; only the write itself can fail. */
fun interface PreparedBackup {
    /** Writes the snapshot to a caller-owned [output], which stays open. */
    suspend fun writeTo(output: OutputStream)
}
