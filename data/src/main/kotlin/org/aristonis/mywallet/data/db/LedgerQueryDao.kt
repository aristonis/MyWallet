package org.aristonis.mywallet.data.db

import androidx.room.Dao
import androidx.room.Query
import kotlinx.coroutines.flow.Flow
import java.time.LocalDate

/**
 * The account-leg projection behind every balance. The leg is read from the `(accountId, amount)`
 * index alone, never from the table, so the cost stays flat as history grows; the currency comes
 * from the account, which every leg on it shares, so it does not need a place in that index. A leg
 * whose account row is gone has no balance to land in and is left out, as before the switch.
 */
const val ACCOUNT_LEGS_SQL =
    "SELECT l.accountId AS accountId, l.amount AS amount, a.currencyCode AS currencyCode " +
        "FROM entry_legs AS l JOIN accounts AS a ON a.id = l.accountId"

/** The debt-leg projection behind every remaining amount, read from the `(debtId, amount)` index the same way. */
const val DEBT_LEGS_SQL =
    "SELECT l.debtId AS debtId, l.amount AS amount, d.currencyCode AS currencyCode, d.side AS side " +
        "FROM entry_legs AS l JOIN debts AS d ON d.id = l.debtId"

/** Whether any leg is on an account. An EXISTS stops at the first index entry instead of counting them all. */
const val ACCOUNT_IN_USE_SQL = "SELECT EXISTS (SELECT 1 FROM entry_legs WHERE accountId = :accountId)"

/** One account leg: its signed amount and the account's currency. */
data class AccountLegRow(val accountId: Long, val amount: String, val currencyCode: String)

/** One debt leg: its signed amount, and the debt's currency and side. */
data class DebtLegRow(val debtId: Long, val amount: String, val currencyCode: String, val side: String)

/**
 * One leg that is on neither an account nor a debt, which is what a category leg is. [categoryId]
 * is null only on a leg that is on nothing at all; [categoryKind] is null when the category row is
 * gone.
 */
data class CategoryLegRow(
    val entryId: Long,
    val date: LocalDate,
    val entryKind: String,
    val role: String,
    val categoryId: Long?,
    val subCategoryId: Long?,
    val categoryKind: String?,
    val amount: String,
    val currencyCode: String,
    val rateToBase: String?,
)

/** A stored entry no recipe reads. */
data class UnknownKindRow(val id: Long, val kind: String)

/** How many entries have a category leg on [categoryId], as main or sub-category. */
data class CategoryUsageRow(val categoryId: Long, val entryCount: Int)

/** A currency and one rate stored on its category legs. */
data class LegRateRow(val currencyCode: String, val rateToBase: String?)

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

    @Query(
        "SELECT e.id AS entryId, e.date AS date, e.kind AS entryKind, l.role AS role, l.categoryId AS categoryId, " +
            "l.subCategoryId AS subCategoryId, c.kind AS categoryKind, l.amount AS amount, " +
            "l.currencyCode AS currencyCode, l.rateToBase AS rateToBase " +
            "FROM entries AS e JOIN entry_legs AS l ON l.entryId = e.id " +
            "LEFT JOIN categories AS c ON c.id = l.categoryId " +
            "WHERE e.date BETWEEN :start AND :end AND l.accountId IS NULL AND l.debtId IS NULL",
    )
    fun observeCategoryLegs(start: LocalDate, end: LocalDate): Flow<List<CategoryLegRow>>

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
}
