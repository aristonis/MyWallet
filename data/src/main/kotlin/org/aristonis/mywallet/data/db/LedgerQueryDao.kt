package org.aristonis.mywallet.data.db

import androidx.room.Dao
import androidx.room.Query
import kotlinx.coroutines.flow.Flow
import java.time.LocalDate

/**
 * The account-leg projection behind every balance. The leg is read from the `(accountId, amount)`
 * index alone, never from the table, so the cost stays flat as history grows. The currency comes
 * from the account, so it does not need a place in that index. That relies on every leg being in
 * its account's currency, an invariant enforced where legs come in: a save refuses a leg in another
 * currency, and so does a restore. A leg whose account row is gone has no balance to land in and is
 * left out, as before the switch.
 */
const val ACCOUNT_LEGS_SQL =
    "SELECT l.accountId AS accountId, l.amount AS amount, a.currencyCode AS currencyCode " +
        "FROM entry_legs AS l JOIN accounts AS a ON a.id = l.accountId"

/** The debt-leg projection behind every remaining amount, read from the `(debtId, amount)` index the same way. */
const val DEBT_LEGS_SQL =
    "SELECT l.debtId AS debtId, l.amount AS amount, d.currencyCode AS currencyCode, d.side AS side " +
        "FROM entry_legs AS l JOIN debts AS d ON d.id = l.debtId"

/** The columns both category-leg reads return, by the names [CategoryLegRow] expects. */
private const val CATEGORY_LEG_COLUMNS =
    "SELECT e.id AS entryId, e.date AS date, e.kind AS entryKind, l.role AS role, l.categoryId AS categoryId, " +
        "l.subCategoryId AS subCategoryId, l.amount AS amount, " +
        "l.currencyCode AS currencyCode, l.rateToBase AS rateToBase "

/**
 * Every category leg of entries dated in `:start`..`:end`, in no particular order. Tracking sums
 * them and picks the newest leg with no rate itself, from each leg's date and entry id, so the read
 * does not sort. Tracking places a leg by its entry's kind, so the category row is not joined.
 *
 * `CROSS JOIN` keeps `entries` as the outer loop, so the date index leads and a month costs its own
 * legs, each entry's found through the `(entryId, ...)` index. The unary `+` on the two `IS NULL`
 * terms keeps SQLite from using a leg index for them: with no table statistics it would otherwise
 * consider the `debtId` index, where almost every leg is NULL, and walk the whole history.
 */
internal const val CATEGORY_LEGS_SQL =
    CATEGORY_LEG_COLUMNS +
        "FROM entries AS e CROSS JOIN entry_legs AS l ON l.entryId = e.id " +
        "WHERE e.date BETWEEN :start AND :end AND +l.accountId IS NULL AND +l.debtId IS NULL"

/**
 * Every category leg of every entry, the same rows [CATEGORY_LEGS_SQL] returns month by month. When
 * every entry is wanted the date index buys nothing: walking it and seeking each entry's legs costs
 * far more than one pass over the legs, each finding its entry by primary key. `CROSS JOIN` keeps
 * the legs as the outer loop.
 */
internal const val CATEGORY_LEGS_ALL_TIME_SQL =
    CATEGORY_LEG_COLUMNS +
        "FROM entry_legs AS l CROSS JOIN entries AS e ON e.id = l.entryId " +
        "WHERE +l.accountId IS NULL AND +l.debtId IS NULL"

/**
 * Whether anything points at an account: a leg, or the raw copy of an old row the upgrade could not
 * read, which still names the accounts it was on. Deleting the account, or changing its currency,
 * would break that copy. Each EXISTS stops at the first match; the leg branch uses the account index.
 */
const val ACCOUNT_IN_USE_SQL =
    "SELECT EXISTS (SELECT 1 FROM entry_legs WHERE accountId = :accountId) " +
        "OR EXISTS (SELECT 1 FROM legacy_unreadable_rows " +
        "WHERE primaryAccountId = :accountId OR secondaryAccountId = :accountId)"

/** One account leg: its signed amount and the account's currency. */
data class AccountLegRow(val accountId: Long, val amount: String, val currencyCode: String)

/** One debt leg: its signed amount, and the debt's currency and side. */
data class DebtLegRow(val debtId: Long, val amount: String, val currencyCode: String, val side: String)

/**
 * One leg that is on neither an account nor a debt, which is what a category leg is. [categoryId]
 * is null only on a leg that is on nothing at all.
 */
data class CategoryLegRow(
    val entryId: Long,
    val date: LocalDate,
    val entryKind: String,
    val role: String,
    val categoryId: Long?,
    val subCategoryId: Long?,
    val amount: String,
    val currencyCode: String,
    val rateToBase: String?,
)

/** A stored entry no recipe reads. */
data class UnknownKindRow(val id: Long, val kind: String)

/** The entry an account leg belongs to, named when that leg cannot be read. */
data class LegOwnerRow(val id: Long, val kind: String)

/** How many entries have a category leg on [categoryId], as main or sub-category. */
data class CategoryUsageRow(val categoryId: Long, val entryCount: Int)

/** A currency and one rate stored on its category legs. */
data class LegRateRow(val currencyCode: String, val rateToBase: String?)

/** One category leg's id with its currency and the exact text of its stored rate, if any. */
data class CategoryLegRateRow(val id: Long, val currencyCode: String, val rateToBase: String?)

/**
 * Read-only projections over legs. SQL only filters and projects here: no SUM, because SQLite sums
 * TEXT through floating point and the amounts are exact decimals. The callers sum with BigDecimal.
 */
@Dao
interface LedgerQueryDao {

    @Query(ACCOUNT_LEGS_SQL)
    fun observeAccountLegs(): Flow<List<AccountLegRow>>

    @Query(DEBT_LEGS_SQL)
    fun observeDebtLegs(): Flow<List<DebtLegRow>>

    @Query(CATEGORY_LEGS_SQL)
    fun observeCategoryLegs(start: LocalDate, end: LocalDate): Flow<List<CategoryLegRow>>

    @Query(CATEGORY_LEGS_ALL_TIME_SQL)
    fun observeAllCategoryLegs(): Flow<List<CategoryLegRow>>

    /**
     * The entry holding a leg on [accountId] whose stored text is exactly [amount], lowest id first.
     * Only asked after that amount failed to parse, so the balance projection itself never needs to
     * carry entry ids; the `(accountId, amount)` index answers it.
     */
    @Query(
        "SELECT e.id AS id, e.kind AS kind FROM entry_legs AS l JOIN entries AS e ON e.id = l.entryId " +
            "WHERE l.accountId = :accountId AND l.amount = :amount ORDER BY e.id LIMIT 1",
    )
    suspend fun findAccountLegOwner(accountId: Long, amount: String): LegOwnerRow?

    /** The first entry, by id, whose kind is not in [known]; null when there is none. */
    @Query("SELECT id, kind FROM entries WHERE kind NOT IN (:known) ORDER BY id LIMIT 1")
    fun observeFirstUnknownKind(known: List<String>): Flow<UnknownKindRow?>

    @Query(ACCOUNT_IN_USE_SQL)
    suspend fun isAccountInUse(accountId: Long): Boolean

    /**
     * Distinct entries per category, counting a leg under its main category and its sub-category.
     * DISTINCT keeps an entry with two legs on one category, or a leg naming the same id twice, at one.
     */
    @Query(
        "SELECT categoryId, COUNT(DISTINCT entryId) AS entryCount FROM (" +
            "SELECT categoryId, entryId FROM entry_legs WHERE categoryId IS NOT NULL " +
            "UNION ALL SELECT subCategoryId AS categoryId, entryId FROM entry_legs WHERE subCategoryId IS NOT NULL" +
            ") GROUP BY categoryId",
    )
    fun observeCategoryUsage(): Flow<List<CategoryUsageRow>>

    /** Stores [rate] on every category leg in [code] that has none yet; legs with a rate keep theirs. */
    @Query(
        "UPDATE entry_legs SET rateToBase = :rate " +
            "WHERE currencyCode = :code AND rateToBase IS NULL AND categoryId IS NOT NULL",
    )
    suspend fun fillMissingRate(code: String, rate: String): Int

    @Query("SELECT DISTINCT currencyCode, rateToBase FROM entry_legs WHERE categoryId IS NOT NULL")
    suspend fun distinctLegRates(): List<LegRateRow>

    /** Every category leg with its stored rate, for rewriting rates on a base change. */
    @Query("SELECT id, currencyCode, rateToBase FROM entry_legs WHERE categoryId IS NOT NULL")
    suspend fun categoryLegRates(): List<CategoryLegRateRow>

    /**
     * Stores [rate] on the legs with [ids], returning how many changed. Callers keep [ids] under
     * SQLite's bound-variable limit.
     */
    @Query("UPDATE entry_legs SET rateToBase = :rate WHERE id IN (:ids)")
    suspend fun setLegRate(ids: List<Long>, rate: String): Int
}
